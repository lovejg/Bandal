package com.bandal.participation;

import com.bandal.common.NotFoundException;
import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.participation.dto.*;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 방에 들어오고 나간다. 메뉴 담기도 나중에 여기로 온다
@Service
@RequiredArgsConstructor
public class ParticipationService {

    private final ParticipationRepository participationRepository;
    private final UserRepository userRepository;
    private final GroupOrderRepository groupOrderRepository;
    private final OrderItemRepository orderItemRepository;

    // 방 참여
    @Transactional
    public ParticipationResponse join(Long groupOrderId, Long userId) {
         GroupOrder groupOrder = groupOrderRepository.findById(groupOrderId)
             .orElseThrow(() -> new NotFoundException("없는 방입니다"));
         User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("없는 사용자입니다"));

         if(participationRepository.existsByGroupOrderIdAndUserId(groupOrderId, userId)) {
             throw new IllegalStateException("이미 참여한 방입니다");
         }

         Instant now = Instant.now();
         groupOrder.checkJoinable(user, participationRepository.countByGroupOrderId(groupOrderId), now);

         Participation participation = new Participation(groupOrder, user, now);
         participationRepository.save(participation);
         return ParticipationResponse.of(participation);
    }

    // 방 나가기(방장은 나갈 수 없음. 반드시 취소해야 됨)
    @Transactional
    public void leave(Long participationId, Long userId) {
        Participation participation = participationRepository.findById(participationId)
            .orElseThrow(() -> new NotFoundException("없는 참여입니다"));

        participation.checkLeavable(userId, Instant.now());

        orderItemRepository.deleteByParticipationId(participationId);
        participationRepository.delete(participation);
    }

    // 메뉴 담기
    @Transactional
    public ParticipationItemsResponse addItem(Long participationId, Long userId, OrderItemRequest request) {
        Participation participation = participationRepository.findById(participationId)
            .orElseThrow(() -> new NotFoundException("없는 참여입니다"));

        OrderItem item = participation.addItem(userId, request.menuName(),
            request.options(), request.unitPrice(), request.quantity(), Instant.now());
        orderItemRepository.save(item);

        return ParticipationItemsResponse.of(participationId,
            orderItemRepository.findByParticipationId(participationId),
            orderItemRepository.sumAmountByGroupOrderId(participation.getGroupOrder().getId()));
    }

    // 메뉴 고치기. 주인(모집중) 또는 방장(마감)
    @Transactional
    public ParticipationItemsResponse updateItem(Long orderItemId, Long userId, OrderItemRequest request) {
        OrderItem item = orderItemRepository.findById(orderItemId)
            .orElseThrow(() -> new NotFoundException("없는 메뉴입니다"));

        item.update(userId, request.menuName(), request.options(),
            request.unitPrice(), request.quantity(), Instant.now());

        Participation participation = item.getParticipation();

        return ParticipationItemsResponse.of(participation.getId(),
            orderItemRepository.findByParticipationId(participation.getId()),
            orderItemRepository.sumAmountByGroupOrderId(participation.getGroupOrder().getId()));
    }

    // 메뉴 빼기
    @Transactional
    public ParticipationItemsResponse removeItem(Long orderItemId, Long userId) {
        OrderItem item = orderItemRepository.findById(orderItemId)
            .orElseThrow(() -> new NotFoundException("없는 메뉴입니다"));
        Participation participation = item.getParticipation();
        Long participationId = participation.getId();

        participation.removeItem(userId, item, Instant.now());
        orderItemRepository.delete(item);

        return ParticipationItemsResponse.of(participationId,
            orderItemRepository.findByParticipationId(participationId),
            orderItemRepository.sumAmountByGroupOrderId(participation.getGroupOrder().getId()));
    }

    // 내 메뉴 조회(나 자신과 방장만 가능)
    @Transactional(readOnly = true)
    public ParticipationItemsResponse findItems(Long participationId, Long userId) {
        Participation participation = participationRepository.findById(participationId)
            .orElseThrow(() -> new NotFoundException("없는 참여입니다"));

        participation.checkItemsViewable(userId);

        return ParticipationItemsResponse.of(participationId,
            orderItemRepository.findByParticipationId(participationId),
            orderItemRepository.sumAmountByGroupOrderId(participation.getGroupOrder().getId()));
    }

    // 방장의 전체 검수
    @Transactional(readOnly = true)
    public GroupOrderItemsResponse findReviewList(Long groupOrderId, Long userId) {
        GroupOrder groupOrder = groupOrderRepository.findById(groupOrderId)
            .orElseThrow(() -> new NotFoundException("없는 방입니다"));
        if(!groupOrder.isHost(userId)) throw new IllegalStateException("방장만 볼 수 있습니다");

        // 모든 참여 및 사용자 다 가져오기
        List<Participation> participations = participationRepository.findRosterWithUser(groupOrderId);

        // 메뉴도 다 가져오기(반복문 밖에서 한번에). 아직 참여별로 분류는 안 된 상태
        List<OrderItem> items = orderItemRepository.findByGroupOrderId(groupOrderId);

        // groupingBy 이용해서 참여별로 분류하기
        Map<Long, List<OrderItem>> itemsByParticipation = items.stream()
            .collect(Collectors.groupingBy(oi -> oi.getParticipation().getId()));

        List<ParticipantItemsResponse> participants = participations.stream()
            .map(p -> ParticipantItemsResponse.of(p,
                itemsByParticipation.getOrDefault(p.getId(), List.of()),
                groupOrder.isHost(p.getUser().getId())))
            .toList();

        return GroupOrderItemsResponse.of(groupOrder, participants);
    }
}
