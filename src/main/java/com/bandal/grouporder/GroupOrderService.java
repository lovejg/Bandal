package com.bandal.grouporder;

import com.bandal.common.NotFoundException;
import com.bandal.grouporder.dto.CreateGroupOrderRequest;
import com.bandal.grouporder.dto.GroupOrderListResponse;
import com.bandal.grouporder.dto.GroupOrderResponse;
import com.bandal.grouporder.dto.GroupOrderSummaryResponse;
import com.bandal.participation.GroupOrderStat;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.Participation;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpot;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.university.University;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

    // 방 생성
    @Transactional
    public GroupOrderResponse create(Long hostId, CreateGroupOrderRequest request) {
        User host = userRepository.findById(hostId).orElseThrow(() -> new NotFoundException("없는 사용자입니다"));
        PickupSpot pickupSpot = pickupSpotRepository.findById(request.pickupSpotId())
            .orElseThrow(() -> new NotFoundException("없는 수령지입니다"));
        GroupOrder groupOrder = new GroupOrder(host, pickupSpot, request.storeName(),
            request.minOrderAmount(), request.deadlineAt(), request.capacity());
        groupOrderRepository.save(groupOrder);
        Participation participation = new Participation(groupOrder, host, Instant.now());
        participationRepository.save(participation);
        return GroupOrderResponse.of(groupOrder, 1, 0, participation.getId());
    }

    // 방 상세 조회(단 건)
    @Transactional(readOnly = true)
    public GroupOrderResponse find(Long groupOrderId, Long userId) {
        GroupOrder groupOrder = groupOrderRepository.findById(groupOrderId)
            .orElseThrow(() -> new NotFoundException("없는 방입니다"));

        Long myParticipationId = participationRepository.findByGroupOrderIdAndUserId(groupOrderId, userId)
            .map(Participation::getId)
            .orElse(null);

        return GroupOrderResponse.of(groupOrder, participationRepository.countByGroupOrderId(groupOrderId),
            orderItemRepository.sumAmountByGroupOrderId(groupOrderId), myParticipationId);
    }

    // 방 목록 조회
    @Transactional(readOnly = true)
    public GroupOrderListResponse findList(Long userId, int page, int size) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("없는 사용자입니다"));
        Long universityId = user.getUniversity().getId();

        // 0부터 시작해서 방 한 페이지 가져오기
        Pageable pageable = PageRequest.of(page, size);
        Slice<GroupOrder> slice = groupOrderRepository.findRecruiting(universityId, Instant.now(), pageable);
        List<GroupOrder> results = slice.getContent();

        List<Long> ids = results.stream().map(GroupOrder::getId).toList();

        // 쿼리 이용해서 참여인원 합계와 메뉴 가격 합계를 받아서 Map 자료형으로 변경(id를 이용하기 위해)
        // 기본적으로 GROUP BY로 얻은 건 순서나 개수가 안 맞아서 이렇게 Map으로 바꿔서 id 이용하는 식으로 하는게 좋음
        Map<Long, Long> counts = participationRepository.countByGroupOrderIds(ids).stream()
            .collect(Collectors.toMap(GroupOrderStat::groupOrderId, GroupOrderStat::value));
        Map<Long, Long> sums = orderItemRepository.sumAmountByGroupOrderIds(ids).stream()
            .collect(Collectors.toMap(GroupOrderStat::groupOrderId, GroupOrderStat::value));

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

        return new GroupOrderListResponse(rooms, slice.hasNext());
    }
}
