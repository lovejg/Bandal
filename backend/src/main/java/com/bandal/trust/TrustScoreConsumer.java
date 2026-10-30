package com.bandal.trust;

import com.bandal.outbox.GroupOrderStatusPayload;
import com.bandal.outbox.GroupOrderStatusPublisher;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

// 신뢰도 점수 컨슈머. 알림, 통계와 같은 방 상태 토픽을 자기 그룹(trust-score)으로 따로 읽는다
// 점수 계산이 밀리거나 실패해도 알림과 통계는 상관없다. 반대도 같다
// 상태를 바꾸는 서비스(방장 취소, 스케줄러 자동 취소, 배달완료)에 점수 코드를 넣지 않은 이유:
// 그 트랜잭션들이 점수 규칙을 몰라도 되고, 규칙을 바꿔도 상태 변경 코드는 그대로다. 이미 나가는 글을 하나 더 읽을 뿐이다
// worker 컨테이너에서만 켠다(app.kafka.consumers-enabled). 실패 처리(다시 하기, DLT)는 KafkaErrorHandlingConfig를 같이 쓴다
@Component
@RequiredArgsConstructor
public class TrustScoreConsumer {

    private final TrustScoreService trustScoreService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            id = "trust-score",
            groupId = "trust-score",
            topics = "${app.kafka.group-order-status-topic}",
            autoStartup = "${app.kafka.consumers-enabled}")
    public void on(ConsumerRecord<String, String> record) {
        Long eventId = GroupOrderStatusPublisher.eventIdOf(record);
        GroupOrderStatusPayload payload = objectMapper.readValue(record.value(), GroupOrderStatusPayload.class);
        trustScoreService.record(eventId, payload);
    }
}
