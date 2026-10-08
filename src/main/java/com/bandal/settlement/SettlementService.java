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
import org.aspectj.weaver.ast.Not;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

        return createLines(groupOrder, request.deliveryFee());
    }

    // 정산 정보 조회. 방장은 전원의 줄, 참여자는 자기 줄만
    @Transactional(readOnly = true)
    public SettlementResponse find(Long groupOrderId, Long requesterId) {
        GroupOrder groupOrder = groupOrderRepository.findById(groupOrderId)
            .orElseThrow(() -> new NotFoundException("없는 방입니다"));

        groupOrder.checkSettlementVisible();

        List<Settlement> all = settlementRepository.findByGroupOrderId(groupOrderId);

        long menuTotalAmount = all.stream().mapToLong(Settlement::getMenuTotalAmount).sum();

        List<Settlement> result;
        if(groupOrder.isHost(requesterId)) {
            result = all;
        }
        else {
            Settlement mine = all.stream()
                .filter(s -> Objects.equals(s.getUser().getId(), requesterId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("없는 방입니다"));
            result = List.of(mine);
        }

        return SettlementResponse.of(groupOrder, menuTotalAmount, result);
    }

    // 참여자가 입금하고 나서 입금확인 버튼
    @Transactional
    public SettlementResponse markPaid(Long settlementId, Long requesterId) {
        Settlement settlement = settlementRepository.findById(settlementId)
            .orElseThrow(() -> new NotFoundException("없는 정산입니다"));

        settlement.markPaid(requesterId, Instant.now());

        return find(settlement.getGroupOrder().getId(), requesterId);
    }

    // 방장이 받고 나서 확인 버튼
    @Transactional
    public SettlementResponse confirm(Long settlementId, Long requesterId) {
        Settlement settlement = settlementRepository.findById(settlementId)
            .orElseThrow(() -> new NotFoundException("없는 정산입니다"));

        settlement.confirm(requesterId, Instant.now());

        return find(settlement.getGroupOrder().getId(), requesterId);
    }

    // 방장이 입금 확인을 취소
    @Transactional
    public SettlementResponse revokeConfirm(Long settlementId, Long requesterId) {
        Settlement settlement = settlementRepository.findById(settlementId)
            .orElseThrow(() -> new NotFoundException("없는 정산입니다"));

        settlement.revokeConfirm(requesterId, Instant.now());

        return find(settlement.getGroupOrder().getId(), requesterId);
    }

    // 방장의 배달비 수정
    @Transactional
    public SettlementResponse changeDeliveryFee(Long groupOrderId, Long requesterId, DeliveryFeeRequest request) {
        GroupOrder groupOrder = groupOrderRepository.findById(groupOrderId)
            .orElseThrow(() -> new NotFoundException("없는 방입니다"));

        groupOrder.changeDeliveryFee(requesterId, request.deliveryFee());

        List<Settlement> lines = settlementRepository.findByGroupOrderId(groupOrderId);
        if(lines.stream().anyMatch(Settlement::hasPaymentRecord)) {
            throw new IllegalStateException("입금 기록이 있어 배달비를 수정할 수 없습니다");
        }

        settlementRepository.deleteByGroupOrderId(groupOrderId); // 정산표 지우고 밑에서 재생성

        return createLines(groupOrder, request.deliveryFee());
    }

    // 정산표 생성 헬퍼
    private SettlementResponse createLines(GroupOrder groupOrder, long deliveryFee) {
        List<Participation> roster = participationRepository.findRosterWithUser(groupOrder.getId());
        List<ParticipantOrder> orders = roster.stream()
            .map(r -> new ParticipantOrder(
                r.getUser().getId(),
                groupOrder.isHost(r.getUser().getId()),
                orderItemRepository.sumAmountByParticipationId(r.getId())))
            .toList();

        List<SettlementResult> results = calculator.calculate(orders, deliveryFee);

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
