package com.bandal.outbox;

import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.grouporder.GroupOrderStatusChanged;
import com.bandal.participation.ParticipationRepository;
import com.bandal.settlement.Settlement;
import com.bandal.settlement.SettlementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 방 상태가 바뀌거나 배달비가 고쳐지면 그 순간의 사실을 outbox에 한 줄 적는다
// 커밋 직전(BEFORE_COMMIT)에 상태를 바꾼 서비스의 트랜잭션 안에서 돈다. 그래서 정산표처럼 서비스가 뒤에 만든 것도 읽을 수 있고,
// 상태 변경과 이 줄은 함께 커밋되거나 함께 사라진다. 여기서 예외가 나면 상태 변경도 롤백된다
@Component
@RequiredArgsConstructor
public class GroupOrderStatusOutboxWriter {

    private final GroupOrderRepository groupOrderRepository;
    private final ParticipationRepository participationRepository;
    private final SettlementRepository settlementRepository;
    private final OutboxMessageRepository outboxMessageRepository;
    private final ObjectMapper objectMapper;


    // 받은 이벤트로 payload를 만들어 JSON으로 바꾸고, 저장까지
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(GroupOrderStatusChanged event) {
        GroupOrder groupOrder = groupOrderRepository.findByIdOrThrow(event.groupOrderId());

        // settlement를 통해 유저마다 얼마 내야 되는지 구하기
        Map<Long, Long> amounts = new HashMap<>(); // <누가, 얼마>
        for(Settlement line : settlementRepository.findByGroupOrderId(groupOrder.getId())) {
            amounts.put(line.getUser().getId(), line.getTotalAmount());
        }

        // participation을 통해서 참여자 목록 구하기 + 위에서 구한 금액 찾아서 넣어준 최종 참여자 리스트 만들기
        List<GroupOrderStatusPayload.Participant> participants = participationRepository
            .findRosterWithUser(groupOrder.getId()).stream()
            .map(p -> new GroupOrderStatusPayload.Participant(p.getUser().getId(), amounts.get(p.getUser().getId())))
            .toList();

        GroupOrderStatusPayload payload = new GroupOrderStatusPayload(
            groupOrder.getId(),
            groupOrder.getPickupSpot().getId(),
            groupOrder.getStoreName(),
            groupOrder.getHost().getId(),
            event.from(),
            event.to(),
            event.changedBy(),
            event.kind(), // 상태 변경인지 배달비 수정인지. 배달비 수정이면 위 정산표 금액이 새로 계산된 금액이다
            groupOrder.getCancelType(),
            groupOrder.getCancelReason(),
            groupOrder.getDeliveryFee(),
            groupOrder.getExpectedArrivalAt(),
            participants,
            event.occurredAt()
        );

        outboxMessageRepository.save(OutboxMessage.groupOrderStatus(
            groupOrder.getId(), objectMapper.writeValueAsString(payload), event.occurredAt())); // objectMapper 이용해서 직렬화
    }
}
