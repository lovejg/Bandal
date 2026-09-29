package com.bandal.settlement;

import com.bandal.common.NotFoundException;
import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.ParticipationRepository;
import com.bandal.settlement.dto.DeliveryFeeRequest;
import com.bandal.settlement.dto.HostAccountResponse;
import com.bandal.settlement.dto.SettlementLineResponse;
import com.bandal.settlement.dto.SettlementResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// 정산표를 만들고 보여주고, 입금 표시와 확인을 받는다
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final GroupOrderRepository groupOrderRepository;
    private final ParticipationRepository participationRepository;
    private final OrderItemRepository orderItemRepository;

    // 계산기는 스프링도 JPA도 모르는 순수 클래스다. 빈으로 등록하지 않고 그냥 쓴다.
    // 초기화된 final 필드라서 롬복이 생성자 인자에 넣지 않는다
    private final SettlementCalculator calculator = new SettlementCalculator();

    // 방장이 배달비를 입력한다. 정산이 시작되고 정산표가 만들어진다
    // TODO: 아래 순서로 채운다.
    //  1. 방을 찾는다. 없으면 NotFoundException("없는 방입니다")
    //  2. 이미 보냈다고 표시한 사람 수를 센다
    //     (settlementRepository.countByGroupOrderIdAndMarkedPaidAtIsNotNull)
    //  3. groupOrder.startSettlement(requesterId, 배달비, 결제금액, 2번에서 센 수)
    //     상태 검사와 방장 검사는 엔티티가 한다. 서비스는 숫자를 모아서 넘긴다
    //  4. 정산표를 통째로 지운다(settlementRepository.deleteByGroupOrderId).
    //     배달비 재입력이면 옛 정산표가 남아 있다. 고치지 않고 지우고 다시 만든다 (ADR-030)
    //  5. collectParticipants로 참여자 목록을 만들고 calculator.calculate(...)로 금액을 구한다
    //  6. 결과를 Settlement 행으로 바꿔 saveAll 한다. 방장 행도 만든다 (ADR-030)
    //     방장인지는 5번에서 만든 목록과 맞춰야 한다
    //  7. find(...)와 같은 모양의 응답을 돌려준다
    @Transactional
    public SettlementResponse startSettlement(Long groupOrderId, Long requesterId,
                                              DeliveryFeeRequest request) {
        GroupOrder groupOrder = groupOrderRepository.findById(groupOrderId)
            .orElseThrow(() -> new NotFoundException("없는 방입니다"));
        long sentCount = settlementRepository.countByGroupOrderIdAndMarkedPaidAtIsNotNull(groupOrderId);
        groupOrder.startSettlement(requesterId, request.deliveryFee(), request.totalPaidAmount(), );
    }

    // 방의 명단을 계산기가 먹을 수 있는 값으로 바꾼다
    // TODO: 아래를 채운다.
    //  - participationRepository.findByGroupOrderId로 명단을 가져온다
    //  - 각 참여자의 메뉴 합계는 orderItemRepository.sumAmountByParticipationId로 구한다
    //  - 방장인지는 participation의 user id와 groupOrder의 host id를 비교한다.
    //    둘 다 지연로딩 프록시일 수 있으니 엔티티끼리 ==로 비교하지 말고 id를 Objects.equals로 비교한다
    private List<ParticipantOrder> collectParticipants(GroupOrder groupOrder) {
        return List.of();
    }

    // 정산 정보 보기. 방장은 전원의 줄을, 참여자는 자기 줄만 본다
    // TODO: 아래를 채운다.
    //  1. 방을 찾는다
    //  2. groupOrder.isSettlementStarted()가 false면
    //     IllegalStateException("아직 정산이 시작되지 않았습니다")
    //  3. 요청자가 그 방 참여자인지 본다
    //     (participationRepository.existsByGroupOrderIdAndUserId).
    //     아니면 NotFoundException("없는 방입니다")를 던진다.
    //     "있지만 못 본다"고 알려줄 이유가 없다. 여기엔 방장 계좌번호가 들어 있다 (ADR-031)
    //  4. 요청자가 방장이면 모든 줄, 아니면 자기 줄 하나만 담는다
    //  5. SettlementResponse를 만든다. 방 전체 메뉴 합계는
    //     orderItemRepository.sumAmountByGroupOrderId로 구한다
    @Transactional(readOnly = true)
    public SettlementResponse find(Long groupOrderId, Long requesterId) {
        return null;
    }

    // 참여자가 송금하고 "보냈어요"를 누른다
    @Transactional
    public SettlementLineResponse markPaid(Long settlementId, Long requesterId) {
        Settlement settlement = settlementRepository.findById(settlementId)
                .orElseThrow(() -> new NotFoundException("없는 정산 내역입니다"));
        settlement.markPaid(requesterId, java.time.Instant.now());
        return SettlementLineResponse.of(settlement);
    }

    // 방장이 입금을 확인한다
    @Transactional
    public SettlementLineResponse confirm(Long settlementId, Long requesterId) {
        Settlement settlement = settlementRepository.findById(settlementId)
                .orElseThrow(() -> new NotFoundException("없는 정산 내역입니다"));
        settlement.confirm(requesterId, java.time.Instant.now());
        return SettlementLineResponse.of(settlement);
    }

    // 응답 조립. find와 startSettlement가 같이 쓴다
    private SettlementResponse toResponse(GroupOrder groupOrder, List<Settlement> lines) {
        return new SettlementResponse(
                groupOrder.getId(),
                groupOrder.getStatus().name(),
                groupOrder.getDeliveryFee() == null ? 0 : groupOrder.getDeliveryFee(),
                orderItemRepository.sumAmountByGroupOrderId(groupOrder.getId()),
                HostAccountResponse.of(groupOrder.getHost()),
                lines.stream().map(SettlementLineResponse::of).toList()
        );
    }
}
