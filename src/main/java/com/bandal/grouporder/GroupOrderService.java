package com.bandal.grouporder;

import com.bandal.common.NotFoundException;
import com.bandal.grouporder.dto.CreateGroupOrderRequest;
import com.bandal.grouporder.dto.GroupOrderResponse;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.Participation;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpot;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.settlement.SettlementRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

// 방을 만들고, 보여주고, 마감한다. 트랜잭션 경계는 여기다 (ADR-023)
@Service
// final 필드를 받는 생성자를 롬복이 만들어 주고, 스프링이 그 생성자로 의존성을 넣어준다
@RequiredArgsConstructor
public class GroupOrderService {

    private final GroupOrderRepository groupOrderRepository;
    private final ParticipationRepository participationRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;
    private final PickupSpotRepository pickupSpotRepository;
    private final SettlementRepository settlementRepository;

    // 방 만들기. 방장도 참여 행을 하나 갖는다 (ADR-019)
    @Transactional
    public GroupOrderResponse create(Long hostId, CreateGroupOrderRequest request) {
        User host = userRepository.findById(hostId)
            .orElseThrow(() -> new NotFoundException("없는 사용자입니다"));
        // TODO: 계좌가 등록돼 있지 않으면 방을 만들 수 없다 (ADR-031).
        //  방장은 돈을 받는 사람이라 계좌 없이 방을 여는 건 성립하지 않는다.
        //  host.hasAccount()를 쓰고, 메시지는 무엇을 해야 하는지 알려주는 문장으로
        PickupSpot pickupSpot = pickupSpotRepository.findById(request.pickupSpotId())
            .orElseThrow(() -> new NotFoundException("없는 수령 거점입니다"));
        GroupOrder groupOrder = new GroupOrder(host, pickupSpot, request.storeName(),
            request.minOrderAmount(), request.deadlineAt(), request.capacity());
        groupOrderRepository.save(groupOrder);
        Participation participation = new Participation(groupOrder, host, Instant.now());
        participationRepository.save(participation);
        return GroupOrderResponse.of(groupOrder, 1, 0);
    }

    // 방 한 개 보기
    @Transactional(readOnly = true)
    public GroupOrderResponse find(Long groupOrderId) {
        GroupOrder groupOrder = groupOrderRepository.findById(groupOrderId)
            .orElseThrow(() -> new NotFoundException("없는 방입니다"));
        return GroupOrderResponse.of(groupOrder, participationRepository.countByGroupOrderId(groupOrderId),
            orderItemRepository.sumAmountByGroupOrderId(groupOrderId));
    }

    // 방장이 직접 마감
    @Transactional
    public GroupOrderResponse close(Long groupOrderId, Long requesterId) {
        GroupOrder groupOrder = groupOrderRepository.findById(groupOrderId)
            .orElseThrow(() -> new NotFoundException("없는 방입니다"));
        long participantCount = participationRepository.countByGroupOrderId(groupOrderId);
        long menuTotalAmount = orderItemRepository.sumAmountByGroupOrderId(groupOrderId);
        groupOrder.closeByHost(requesterId, participantCount, menuTotalAmount);
        return GroupOrderResponse.of(groupOrder, participantCount, menuTotalAmount);
    }

    // 전원 입금이 확인됐으면 방장이 결제하고 주문완료로 넘긴다 (ADR-029)
    @Transactional
    public GroupOrderResponse order(Long groupOrderId, Long requesterId) {
        GroupOrder groupOrder = groupOrderRepository.findById(groupOrderId)
            .orElseThrow(() -> new NotFoundException("없는 방입니다"));
        // 미확인이 몇 건 남았는지는 DB가 안다. 엔티티는 그 숫자로 판단한다
        long unconfirmedCount = settlementRepository.countByGroupOrderIdAndConfirmedPaidAtIsNull(groupOrderId);
        groupOrder.markOrdered(requesterId, unconfirmedCount);
        return GroupOrderResponse.of(groupOrder,
            participationRepository.countByGroupOrderId(groupOrderId),
            orderItemRepository.sumAmountByGroupOrderId(groupOrderId));
    }

    // 음식이 도착했다
    @Transactional
    public GroupOrderResponse deliver(Long groupOrderId, Long requesterId) {
        GroupOrder groupOrder = groupOrderRepository.findById(groupOrderId)
            .orElseThrow(() -> new NotFoundException("없는 방입니다"));
        groupOrder.markDelivered(requesterId);
        return GroupOrderResponse.of(groupOrder,
            participationRepository.countByGroupOrderId(groupOrderId),
            orderItemRepository.sumAmountByGroupOrderId(groupOrderId));
    }

    // 수정 후 공통함수 만들기
}
