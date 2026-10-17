package com.bandal.grouporder;

import com.bandal.common.NotFoundException;
import com.bandal.grouporder.dto.*;
import com.bandal.participation.GroupOrderStat;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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


    // 방 생성. 방장도 참여 행을 하나 가진다
    @Transactional
    public GroupOrderResponse create(Long hostId, CreateGroupOrderRequest request) {
        User host = userRepository.findByIdOrThrow(hostId);
        PickupSpot pickupSpot = pickupSpotRepository.findByIdOrThrow(request.pickupSpotId());
        GroupOrder groupOrder = new GroupOrder(host, pickupSpot, request.storeName(),
            request.minOrderAmount(), request.deadlineAt(), request.capacity());

        groupOrderRepository.save(groupOrder);
        roomListCache.evict(pickupSpot.getUniversity().getId()); // 캐시 지우기(스레드한테 커밋 이후에 지워달라고 요청하기)

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
    public GroupOrderListResponse findList(Long userId, int page, int size) {
        Long universityId = userRepository.findByIdOrThrow(userId).getUniversity().getId();

        // 해당 요청을 캐시로 받을 수 있는지 확인하고, 되면 캐시 반환
        boolean cacheable = roomListCache.covers(page, size);
        if(cacheable) {
            GroupOrderListResponse cached = roomListCache.get(universityId);
            if(cached != null) {
                return cached;
            }
        }

        // 페이지 번호는 0부터 시작한다
        Slice<GroupOrder> slice = groupOrderRepository.findRecruiting(universityId, Instant.now(), PageRequest.of(page, size));
        List<GroupOrder> results = slice.getContent();
        List<Long> ids = results.stream().map(GroupOrder::getId).toList();

        // GROUP BY 결과는 방 순서와 맞지 않고, 인원이나 메뉴가 없는 방은 줄이 빠진다. 그래서 방 id로 찾는 Map으로 바꾼다
        Map<Long, Long> counts = toMap(participationRepository.countByGroupOrderIds(ids));
        Map<Long, Long> sums = toMap(orderItemRepository.sumAmountByGroupOrderIds(ids));

        List<GroupOrderSummaryResponse> rooms = results.stream()
            .map(r -> new GroupOrderSummaryResponse(
                r.getId(),
                r.getStoreName(),
                r.getPickupSpot().getId(),
                r.getPickupSpot().getName(),
                r.getHost().getNickname(),
                r.getMinOrderAmount(),
                sums.getOrDefault(r.getId(), 0L),
                counts.getOrDefault(r.getId(), 0L),
                r.getCapacity(),
                r.getDeadlineAt()
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
        GroupOrder groupOrder = groupOrderRepository.findByIdForUpdate(groupOrderId)
            .orElseThrow(() -> new NotFoundException("없는 방입니다"));

        long participantCount = participationRepository.countByGroupOrderId(groupOrderId);
        long menuTotalAmount = orderItemRepository.sumAmountByGroupOrderId(groupOrderId);

        groupOrder.closeByHost(requesterId, participantCount, menuTotalAmount, Instant.now());
        roomListCache.evict(groupOrder.getPickupSpot().getUniversity().getId()); // 캐시 지우기

        return GroupOrderResponse.of(groupOrder, participantCount, menuTotalAmount,
            myParticipationId(groupOrderId, requesterId));
    }

    // 방장 취소
    @Transactional
    public GroupOrderResponse cancel(Long groupOrderId, Long requesterId, CancelRequest request) {
        GroupOrder groupOrder = groupOrderRepository.findByIdForUpdate(groupOrderId)
            .orElseThrow(() -> new NotFoundException("없는 방입니다"));

        groupOrder.cancelByHost(requesterId, request.reason(), Instant.now());
        roomListCache.evict(groupOrder.getPickupSpot().getUniversity().getId()); // 캐시 지우기

        return toResponse(groupOrder, requesterId);
    }

    // 스케줄러가 방 하나씩 부른다. 멈춘 방을 다음 상태로 넘긴다
    @Transactional
    public void expire(Long groupOrderId, Instant now) {
        GroupOrder groupOrder = groupOrderRepository.findByIdForUpdate(groupOrderId)
            .orElseThrow(() -> new NotFoundException("없는 방입니다"));

        // 모집중은 추가 정보(participantCount와 menuTotalAmount)가 필요해서 따로
        if(groupOrder.getStatus() == GroupOrderStatus.RECRUITING) {
            long participantCount = participationRepository.countByGroupOrderId(groupOrderId);
            long menuTotalAmount = orderItemRepository.sumAmountByGroupOrderId(groupOrderId);
            groupOrder.closeAtDeadline(participantCount, menuTotalAmount, now);
            roomListCache.evict(groupOrder.getPickupSpot().getUniversity().getId()); // 캐시 지우기
            return;
        }

        groupOrder.expireIfStale(now); // 모집중이 아닌 방은 방 목록에 안 뜨니까 캐시 지울 필요 X
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
    private Map<Long, Long> toMap(List<GroupOrderStat> stats) {
        return stats.stream().collect(Collectors.toMap(GroupOrderStat::groupOrderId, GroupOrderStat::value));
    }
}
