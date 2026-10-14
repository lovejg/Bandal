package com.bandal.settlement;

import com.bandal.common.NotFoundException;
import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.Participation;
import com.bandal.participation.ParticipationRepository;
import com.bandal.settlement.dto.DeliveryFeeRequest;
import com.bandal.settlement.dto.OrderRequest;
import com.bandal.settlement.dto.SettlementResponse;
import com.bandal.user.User;
import lombok.RequiredArgsConstructor;
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

    // 배달비를 고치면 정산표를 지우고 다시 만들어서 줄 id가 바뀐다(ADR-046). 옛 화면에서 누르면 여기 걸린다 (ADR-050)
    private static final String STALE_LINE_MESSAGE = "없는 정산입니다. 배달비가 바뀌어 정산표가 새로 만들어졌을 수 있으니 정산 정보를 다시 불러와 주세요";

    // 방장의 정산(배달비 입력 및 정산표)
    @Transactional
    public SettlementResponse start(Long groupOrderId, Long requesterId, DeliveryFeeRequest request) {
        GroupOrder groupOrder = groupOrderRepository.findByIdOrThrow(groupOrderId);

        groupOrder.startSettlement(requesterId, request.deliveryFee(), Instant.now());

        return createLines(groupOrder, request.deliveryFee());
    }

    // 정산 정보 조회. 방장은 전원의 줄, 참여자는 자기 줄만
    @Transactional(readOnly = true)
    public SettlementResponse find(Long groupOrderId, Long requesterId) {
        GroupOrder groupOrder = groupOrderRepository.findByIdOrThrow(groupOrderId);

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
        Settlement settlement = findLine(settlementId);

        settlement.markPaid(requesterId, Instant.now());

        return find(settlement.getGroupOrder().getId(), requesterId);
    }

    // 방장이 받고 나서 확인 버튼
    @Transactional
    public SettlementResponse confirm(Long settlementId, Long requesterId) {
        Settlement settlement = findLine(settlementId);

        settlement.confirm(requesterId, Instant.now());

        return find(settlement.getGroupOrder().getId(), requesterId);
    }

    // 방장이 입금 확인을 취소
    @Transactional
    public SettlementResponse revokeConfirm(Long settlementId, Long requesterId) {
        Settlement settlement = findLine(settlementId);

        settlement.revokeConfirm(requesterId, Instant.now());

        return find(settlement.getGroupOrder().getId(), requesterId);
    }

    // 방장의 배달비 수정
    @Transactional
    public SettlementResponse changeDeliveryFee(Long groupOrderId, Long requesterId, DeliveryFeeRequest request) {
        GroupOrder groupOrder = groupOrderRepository.findByIdOrThrow(groupOrderId);

        groupOrder.changeDeliveryFee(requesterId, request.deliveryFee());

        List<Settlement> lines = settlementRepository.findByGroupOrderId(groupOrderId);
        if(lines.stream().anyMatch(Settlement::hasPaymentRecord)) {
            throw new IllegalStateException("입금 기록이 있어 배달비를 수정할 수 없습니다");
        }

        settlementRepository.deleteByGroupOrderId(groupOrderId); // 정산표 지우고 밑에서 재생성

        return createLines(groupOrder, request.deliveryFee());
    }

    // 정산 줄 하나. 없으면 배달비 수정으로 정산표가 새로 만들어졌을 수 있다고 알려준다
    private Settlement findLine(Long settlementId) {
        return settlementRepository.findById(settlementId)
            .orElseThrow(() -> new NotFoundException(STALE_LINE_MESSAGE));
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

    // 방장의 주문 완료 버튼(전원 입금 확인됐을 때)
    @Transactional
    public SettlementResponse order(Long groupOrderId, Long requesterId, OrderRequest orderRequest) {
        GroupOrder groupOrder = groupOrderRepository.findByIdOrThrow(groupOrderId);

        groupOrder.order(requesterId, orderRequest.totalPaidAmount(), Instant.now());

        List<Settlement> lines = settlementRepository.findByGroupOrderId(groupOrderId);
        if(!lines.stream().allMatch(Settlement::isConfirmed)) {
            throw new IllegalStateException("입금 확인이 되지 않은 참여자가 있습니다");
        }

        return find(groupOrderId, requesterId);
    }

    // 방장의 배달 완료 버튼(배달 앱을 통해 도착했는지 직접 확인)
    @Transactional
    public SettlementResponse deliver(Long groupOrderId, Long requesterId) {
        GroupOrder groupOrder = groupOrderRepository.findByIdOrThrow(groupOrderId);

        groupOrder.deliver(requesterId, Instant.now());

        return find(groupOrderId, requesterId);
    }

    // 방장의 돌려줬어요 버튼
    @Transactional
    public SettlementResponse sendRefund(Long settlementId, Long requesterId) {
        Settlement settlement = findLine(settlementId);

        settlement.sendRefund(requesterId, Instant.now());

        return find(settlement.getGroupOrder().getId(), requesterId);
    }

    // 방장의 입금 안 됐어요 버튼. 받은 적이 없으니 돌려줄 것도 없다
    @Transactional
    public SettlementResponse denyPayment(Long settlementId, Long requesterId) {
        Settlement settlement = findLine(settlementId);

        settlement.denyPayment(requesterId, Instant.now());

        return find(settlement.getGroupOrder().getId(), requesterId);
    }

    // 참여자의 돌려받았어요 버튼
    @Transactional
    public SettlementResponse receiveRefund(Long settlementId, Long requesterId) {
        Settlement settlement = findLine(settlementId);

        settlement.receiveRefund(requesterId, Instant.now());

        return find(settlement.getGroupOrder().getId(), requesterId);
    }
}
