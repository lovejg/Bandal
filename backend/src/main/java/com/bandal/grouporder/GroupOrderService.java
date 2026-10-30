package com.bandal.grouporder;

import com.bandal.grouporder.dto.*;
import com.bandal.participation.GroupOrderValue;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.Participation;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpot;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.settlement.Settlement;
import com.bandal.settlement.SettlementRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// 방을 만들고, 보여주고, 상태를 넘긴다. 트랜잭션 경계는 여기다
@Service
// final 필드를 받는 생성자를 롬복이 만들어 주고, 스프링이 그 생성자로 의존성을 넣어준다
@RequiredArgsConstructor
public class GroupOrderService {

    private final GroupOrderRepository groupOrderRepository;
    private final ParticipationRepository participationRepository;
    private final UserRepository userRepository;
    private final PickupSpotRepository pickupSpotRepository;
    private final OrderItemRepository orderItemRepository;
    private final SettlementRepository settlementRepository;
    private final RoomListCache roomListCache;

    // 방을 만들 수 있는 최소 신뢰도 점수
    @Value("${app.trust-score.min-to-host}")
    private int minTrustScoreToHost;

    // 방 생성. 방장도 참여 행을 하나 가진다
    @Transactional
    public GroupOrderResponse create(Long hostId, CreateGroupOrderRequest request) {
        User host = userRepository.findByIdOrThrow(hostId);
        // 신뢰도 점수가 기준보다 낮으면 방장이 될 수 없다. 방장은 참여자들의 돈을 먼저 받는 자리다
        // 참여는 막지 않는다. 참여자는 돈을 보내는 쪽이라 위험을 지는 사람이 본인이다
        if (host.getTrustScore() < minTrustScoreToHost) {
            throw new IllegalStateException("신뢰도 점수가 " + minTrustScoreToHost + "점보다 낮아 방을 만들 수 없습니다");
        }
        PickupSpot pickupSpot = pickupSpotRepository.findByIdOrThrow(request.pickupSpotId());
        GroupOrder groupOrder = new GroupOrder(host, pickupSpot, request.storeName(),
            request.minOrderAmount(), request.deadlineAt(), request.capacity());
        groupOrder.chooseCategories(request.categories());

        groupOrderRepository.save(groupOrder);
        roomListCache.evict(pickupSpot.getUniversity().getId()); // 목록 캐시는 커밋 뒤에 지워진다

        Participation participation = new Participation(groupOrder, host, Instant.now());
        participationRepository.save(participation);

        return GroupOrderResponse.of(groupOrder, 1, 0, participation.getId());
    }

    // 방 상세 조회(단 건)
    @Transactional(readOnly = true)
    public GroupOrderResponse find(Long groupOrderId, Long requesterId) {
        GroupOrder groupOrder = groupOrderRepository.findByIdOrThrow(groupOrderId);
        return toResponse(groupOrder, requesterId);
    }

    // 방 목록 조회
    @Transactional(readOnly = true)
    public GroupOrderListResponse findList(Long userId, int page, int size, Set<FoodCategory> categories) {
        Long universityId = userRepository.findByIdOrThrow(userId).getUniversity().getId();
        boolean filtered = categories != null && !categories.isEmpty();

        // 해당 요청을 캐시로 받을 수 있는지 확인하고, 되면 캐시 반환
        // 카테고리로 거른 요청은 캐시하지 않는다. 고르는 조합이 많아(15개 중 여러 개) 같은 요청이 다시 올 일이 드물고 메모리만 먹는다
        // 캐시는 거르지 않은 첫 페이지(가장 많이 열리는 화면) 하나만 맡는다
        boolean cacheable = !filtered && roomListCache.covers(page, size);
        if(cacheable) {
            GroupOrderListResponse cached = roomListCache.get(universityId);
            if(cached != null) {
                return cached;
            }
        }

        // 페이지 번호는 0부터 시작한다
        Slice<GroupOrder> slice = filtered
            ? groupOrderRepository.findRecruitingIn(universityId, Instant.now(), categories, PageRequest.of(page, size))
            : groupOrderRepository.findRecruiting(universityId, Instant.now(), PageRequest.of(page, size));
        List<GroupOrder> results = slice.getContent();
        List<Long> ids = results.stream().map(GroupOrder::getId).toList();

        // GROUP BY 결과는 방 순서와 맞지 않고, 인원이나 메뉴가 없는 방은 줄이 빠진다. 그래서 방 id로 찾는 Map으로 바꾼다
        Map<Long, Long> counts = toMap(participationRepository.countByGroupOrderIds(ids));
        Map<Long, Long> sums = toMap(orderItemRepository.sumAmountByGroupOrderIds(ids));
        // 카테고리도 방마다 따로 읽지 않고 id 목록으로 한 번에. 카테고리가 없는 옛 방은 빈 목록
        Map<Long, List<String>> categoryNames = new HashMap<>();
        for (Object[] row : groupOrderRepository.findCategoriesByIds(ids)) {
            categoryNames.computeIfAbsent((Long) row[0], k -> new ArrayList<>()).add(((FoodCategory) row[1]).name());
        }

        List<GroupOrderSummaryResponse> rooms = results.stream()
            .map(r -> new GroupOrderSummaryResponse(
                r.getId(),
                r.getStoreName(),
                r.getPickupSpot().getId(),
                r.getPickupSpot().getName(),
                r.getHost().displayName(),
                r.getMinOrderAmount(),
                sums.getOrDefault(r.getId(), 0L),
                counts.getOrDefault(r.getId(), 0L),
                r.getCapacity(),
                r.getDeadlineAt(),
                categoryNames.getOrDefault(r.getId(), List.of())
            )).toList();

        // 캐시가 없을 경우 캐시 만들어주기
        GroupOrderListResponse response = new GroupOrderListResponse(rooms, slice.hasNext());
        if(cacheable) {
            roomListCache.put(universityId, response);
        }
        return response;
    }

    // 내 방 목록: 진행 중 탭
    @Transactional(readOnly = true)
    public MyGroupOrderListResponse findMyActive(Long userId) {
        List<GroupOrder> rooms = groupOrderRepository.findMyActive(userId);
        return new MyGroupOrderListResponse(toMyRows(userId, rooms), false);
    }

    // 내 방 목록: 끝난 방 탭
    @Transactional(readOnly = true)
    public MyGroupOrderListResponse findMyDone(Long userId, int page, int size) {
        Slice<GroupOrder> slice = groupOrderRepository.findMyDone(userId, PageRequest.of(page, size));
        return new MyGroupOrderListResponse(toMyRows(userId, slice.getContent()), slice.hasNext());
    }

    // 방장 마감
    @Transactional
    public GroupOrderResponse close(Long groupOrderId, Long requesterId) {
        GroupOrder groupOrder = groupOrderRepository.findByIdForUpdateOrThrow(groupOrderId);

        long participantCount = participationRepository.countByGroupOrderId(groupOrderId);
        long menuTotalAmount = orderItemRepository.sumAmountByGroupOrderId(groupOrderId);

        groupOrder.closeByHost(requesterId, participantCount, menuTotalAmount, Instant.now());
        groupOrderRepository.save(groupOrder); // 상태 변경 기록을 사건으로 내보낸다. 변경 감지만으로는 안 나간다
        roomListCache.evict(groupOrder.getPickupSpot().getUniversity().getId());

        return GroupOrderResponse.of(groupOrder, participantCount, menuTotalAmount,
            myParticipationId(groupOrderId, requesterId));
    }

    // 방장 취소
    @Transactional
    public GroupOrderResponse cancel(Long groupOrderId, Long requesterId, CancelRequest request) {
        GroupOrder groupOrder = groupOrderRepository.findByIdForUpdateOrThrow(groupOrderId);

        groupOrder.cancelByHost(requesterId, request.reason(), Instant.now());
        groupOrderRepository.save(groupOrder); // 상태 변경 기록을 사건으로 내보낸다
        roomListCache.evict(groupOrder.getPickupSpot().getUniversity().getId());

        return toResponse(groupOrder, requesterId);
    }

    // 스케줄러가 방 하나씩 부른다. 멈춘 방을 다음 상태로 넘긴다
    @Transactional
    public void expire(Long groupOrderId, Instant now) {
        GroupOrder groupOrder = groupOrderRepository.findByIdForUpdateOrThrow(groupOrderId);

        // 모집중은 추가 정보(participantCount와 menuTotalAmount)가 필요해서 따로
        if(groupOrder.getStatus() == GroupOrderStatus.RECRUITING) {
            long participantCount = participationRepository.countByGroupOrderId(groupOrderId);
            long menuTotalAmount = orderItemRepository.sumAmountByGroupOrderId(groupOrderId);
            groupOrder.closeAtDeadline(participantCount, menuTotalAmount, now);
            groupOrderRepository.save(groupOrder); // 상태 변경 기록을 사건으로 내보낸다
            roomListCache.evict(groupOrder.getPickupSpot().getUniversity().getId());
            return;
        }

        groupOrder.expireIfStale(now); // 모집중이 아닌 방은 방 목록에 없어서 캐시를 지울 필요가 없다
        groupOrderRepository.save(groupOrder); // 기간이 덜 차 안 바뀌었으면 기록이 없어 아무것도 안 나간다
    }

    // 방 상세 응답. 인원, 메뉴 합계, 보는 사람의 참여 id는 엔티티가 모르는 값이라 여기서 센다
    private GroupOrderResponse toResponse(GroupOrder groupOrder, Long requesterId) {
        Long groupOrderId = groupOrder.getId();
        return GroupOrderResponse.of(groupOrder,
            participationRepository.countByGroupOrderId(groupOrderId),
            orderItemRepository.sumAmountByGroupOrderId(groupOrderId),
            myParticipationId(groupOrderId, requesterId));
    }

    // 보는 사람의 참여 id. 참여하지 않았으면 null
    private Long myParticipationId(Long groupOrderId, Long userId) {
        return participationRepository.findByGroupOrderIdAndUserId(groupOrderId, userId)
            .map(Participation::getId)
            .orElse(null);
    }

    // 내 방 목록의 줄을 만든다. 두 탭이 같이 쓴다
    private List<MyGroupOrderResponse> toMyRows(Long userId, List<GroupOrder> rooms) {
        List<Long> roomIds = rooms.stream().map(GroupOrder::getId).toList();
        // 방 id -> 그 방의 내 정산 줄. 정산표가 아직 없는 방은 없다
        Map<Long, Settlement> myLines = settlementRepository.findMineByGroupOrderIds(userId, roomIds).stream()
            .collect(Collectors.toMap(s -> s.getGroupOrder().getId(), s -> s));
        // 방 id -> 확인을 기다리는 참여자 수. 기다리는 사람이 없는 방은 없다
        Map<Long, Long> awaiting = toMap(settlementRepository.countAwaitingConfirmByGroupOrderIds(roomIds));

        return rooms.stream()
            .map(r -> {
                boolean host = r.isHost(userId);
                // 방장 줄은 만들 때 두 시각이 채워져 있어서 그대로 내보내면 "다 냈음"으로 보인다. 방장이면 안 본다
                Settlement mine = host ? null : myLines.get(r.getId());
                return new MyGroupOrderResponse(
                    r.getId(),
                    r.getStoreName(),
                    r.getStatus().name(),
                    host,
                    mine == null ? null : mine.getMarkedPaidAt(),
                    mine == null ? null : mine.getConfirmedPaidAt(),
                    mine == null ? null : mine.refundStatus(),
                    // 방장이면 숫자(없으면 0), 참여자면 해당 없는 칸이라 null
                    host ? awaiting.getOrDefault(r.getId(), 0L) : null
                );
            })
            .toList();
    }

    // 방 id와 숫자 한 쌍의 목록을 방 id로 찾는 Map으로
    private Map<Long, Long> toMap(List<GroupOrderValue> values) {
        return values.stream().collect(Collectors.toMap(GroupOrderValue::groupOrderId, GroupOrderValue::value));
    }
}
