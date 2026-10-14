package com.bandal.participation;

import com.bandal.common.ForbiddenException;
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

// 방에 들어오고 나가고, 메뉴를 담고 고치고 뺀다
@Service
@RequiredArgsConstructor
public class ParticipationService {

    private final ParticipationRepository participationRepository;
    private final UserRepository userRepository;
    private final GroupOrderRepository groupOrderRepository;
    private final OrderItemRepository orderItemRepository;

    // 방 참여
    @Transactional
    public ParticipationResponse join(Long groupOrderId, Long requesterId) {
        GroupOrder groupOrder = groupOrderRepository.findByIdOrThrow(groupOrderId);
        User user = userRepository.findByIdOrThrow(requesterId);

        if(participationRepository.existsByGroupOrderIdAndUserId(groupOrderId, requesterId)) {
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
    public void leave(Long participationId, Long requesterId) {
        Participation participation = participationRepository.findByIdOrThrow(participationId);

        participation.checkLeavable(requesterId, Instant.now());

        orderItemRepository.deleteByParticipationId(participationId);
        participationRepository.delete(participation);
    }

    // 메뉴 담기
    @Transactional
    public ParticipationItemsResponse addItem(Long participationId, Long requesterId, OrderItemRequest request) {
        Participation participation = participationRepository.findByIdOrThrow(participationId);

        OrderItem item = participation.addItem(requesterId, request.menuName(),
            request.options(), request.unitPrice(), request.quantity(), Instant.now());
        orderItemRepository.save(item);

        return itemsResponse(participation);
    }

    // 메뉴 고치기. 주인(모집중) 또는 방장(마감)
    @Transactional
    public ParticipationItemsResponse updateItem(Long orderItemId, Long requesterId, OrderItemRequest request) {
        OrderItem item = orderItemRepository.findByIdOrThrow(orderItemId);

        item.update(requesterId, request.menuName(), request.options(),
            request.unitPrice(), request.quantity(), Instant.now());

        return itemsResponse(item.getParticipation());
    }

    // 메뉴 빼기
    @Transactional
    public ParticipationItemsResponse removeItem(Long orderItemId, Long requesterId) {
        OrderItem item = orderItemRepository.findByIdOrThrow(orderItemId);
        Participation participation = item.getParticipation();

        participation.removeItem(requesterId, item, Instant.now());
        orderItemRepository.delete(item);

        return itemsResponse(participation);
    }

    // 내 메뉴 조회(나 자신과 방장만 가능)
    @Transactional(readOnly = true)
    public ParticipationItemsResponse findItems(Long participationId, Long requesterId) {
        Participation participation = participationRepository.findByIdOrThrow(participationId);

        participation.checkItemsViewable(requesterId);

        return itemsResponse(participation);
    }

    // 방장의 전체 검수
    @Transactional(readOnly = true)
    public GroupOrderItemsResponse findReviewList(Long groupOrderId, Long requesterId) {
        GroupOrder groupOrder = groupOrderRepository.findByIdOrThrow(groupOrderId);
        if(!groupOrder.isHost(requesterId)) throw new ForbiddenException("방장만 볼 수 있습니다");

        // 명단은 사용자까지, 메뉴는 방 전체를 한 번에 가져온다. 사람마다 묻지 않는다
        List<Participation> participations = participationRepository.findRosterWithUser(groupOrderId);
        List<OrderItem> items = orderItemRepository.findByGroupOrderId(groupOrderId);

        // 참여 id별로 메뉴를 나눈다
        Map<Long, List<OrderItem>> itemsByParticipation = items.stream()
            .collect(Collectors.groupingBy(oi -> oi.getParticipation().getId()));

        List<ParticipantItemsResponse> participants = participations.stream()
            .map(p -> ParticipantItemsResponse.of(p,
                itemsByParticipation.getOrDefault(p.getId(), List.of()),
                groupOrder.isHost(p.getUser().getId())))
            .toList();

        return GroupOrderItemsResponse.of(groupOrder, participants);
    }

    // 메뉴를 담고, 고치고, 빼고, 볼 때 같은 모양으로 돌려준다. 그 참여의 메뉴와 방 전체 합계
    private ParticipationItemsResponse itemsResponse(Participation participation) {
        return ParticipationItemsResponse.of(participation.getId(),
            orderItemRepository.findByParticipationId(participation.getId()),
            orderItemRepository.sumAmountByGroupOrderId(participation.getGroupOrder().getId()));
    }
}
