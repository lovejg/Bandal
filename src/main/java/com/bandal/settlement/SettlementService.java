package com.bandal.settlement;

import com.bandal.common.NotFoundException;
import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.Participation;
import com.bandal.participation.ParticipationRepository;
import com.bandal.settlement.dto.DeliveryFeeRequest;
import com.bandal.settlement.dto.SettlementResponse;
import com.bandal.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 정산표를 만들고, 보여주고, 입금을 기록한다
// Settlement 생성자가 이 패키지 안에서만 열려 있어서 정산표 행은 여기서만 만들 수 있다
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final GroupOrderRepository groupOrderRepository;
    private final ParticipationRepository participationRepository;
    private final OrderItemRepository orderItemRepository;
    private final SettlementRepository settlementRepository;
    private final SettlementCalculator calculator = new SettlementCalculator();

    // 방장의 정산(배달비 입력 및 정산표)
    @Transactional
    public SettlementResponse start(Long groupOrderId, Long requesterId, DeliveryFeeRequest request) {
        GroupOrder groupOrder = groupOrderRepository.findById(groupOrderId)
            .orElseThrow(() -> new NotFoundException("없는 방입니다"));

        groupOrder.startSettlement(requesterId, request.deliveryFee());

        List<Participation> roster = participationRepository.findRosterWithUser(groupOrderId);
        List<ParticipantOrder> orders = roster.stream()
            .map(r -> new ParticipantOrder(
                r.getUser().getId(),
                groupOrder.isHost(r.getUser().getId()),
                orderItemRepository.sumAmountByParticipationId(r.getId())))
            .toList();

        List<SettlementResult> results = calculator.calculate(orders, request.deliveryFee());

        long menuTotalAmount = results.stream().mapToLong(SettlementResult::menuTotalAmount).sum();

        Map<Long, User> users = roster.stream()
            .collect(Collectors.toMap(r -> r.getUser().getId(), Participation::getUser));

        Instant now = Instant.now();
        List<Settlement> lines = results.stream()
            .map(r -> new Settlement(
                groupOrder, users.get(r.userId()), r.menuTotalAmount(), r.deliveryFeeShare(),
                groupOrder.isHost(r.userId()), now
            ))
            .toList();
        settlementRepository.saveAll(lines);

        return SettlementResponse.of(groupOrder, menuTotalAmount, lines);
    }
}
