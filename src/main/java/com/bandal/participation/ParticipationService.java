package com.bandal.participation;

import com.bandal.common.NotFoundException;
import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.participation.dto.AddOrderItemRequest;
import com.bandal.participation.dto.OrderItemResponse;
import com.bandal.participation.dto.ParticipationResponse;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

// 방에 참여하고, 메뉴를 담고 뺀다 (ADR-023)
@Service
@RequiredArgsConstructor
public class ParticipationService {

    private final ParticipationRepository participationRepository;
    private final OrderItemRepository orderItemRepository;
    private final GroupOrderRepository groupOrderRepository;
    private final UserRepository userRepository;

    // 방에 참여한다
    @Transactional
    public ParticipationResponse join(Long groupOrderId, Long userId) {
        GroupOrder groupOrder = groupOrderRepository.findById(groupOrderId)
            .orElseThrow(() -> new NotFoundException("없는 방입니다"));
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new NotFoundException("없는 사용자입니다"));
        if(participationRepository.existsByGroupOrderIdAndUserId(groupOrderId, userId)) {
            throw new IllegalStateException("이미 참여한 방입니다");
        }
        groupOrder.checkJoinable(user, participationRepository.countByGroupOrderId(groupOrderId), Instant.now());
        Participation participation = new Participation(groupOrder, user, Instant.now());
        participationRepository.save(participation);
        return ParticipationResponse.of(participation);
    }

    // 메뉴를 담는다
    @Transactional
    public OrderItemResponse addItem(Long participationId, Long requesterId, AddOrderItemRequest request) {
        Participation participation = participationRepository.findById(participationId)
            .orElseThrow(() -> new NotFoundException("없는 참여입니다"));
        OrderItem orderItem = participation.addItem(requesterId, request.menuName(), request.options(),
            request.unitPrice(), request.quantity());
        orderItemRepository.save(orderItem);
        return OrderItemResponse.of(orderItem);
    }

    // 담은 메뉴를 뺀다
    @Transactional
    public void removeItem(Long orderItemId, Long requesterId) {
        OrderItem orderItem = orderItemRepository.findById(orderItemId)
            .orElseThrow(() -> new NotFoundException("없는 메뉴입니다"));
        orderItem.getParticipation().removeItem(requesterId, orderItem);
        orderItemRepository.deleteById(orderItemId);
    }
}
