package com.bandal.statistics;

import com.bandal.outbox.GroupOrderStatusPayload;
import com.bandal.outbox.GroupOrderStatusPublisher;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

// 통계 컨슈머. 알림 컨슈머와 같은 토픽을 읽지만 그룹(statistics)이 달라서 자기 책갈피로 따로 읽는다
// 알림이 밀리거나 죽어도 통계는 상관없이 간다. 반대도 같다
// 이 그룹만 맨 앞으로 되감아 지난 글을 다시 읽힐 수 있다. 알림 그룹의 책갈피는 그대로라 알림이 다시 가지 않는다
// 알림 컨슈머처럼 worker 컨테이너에서만 켠다(app.kafka.consumers-enabled)
// 실패 처리(다시 하기, DLT)는 알림 컨슈머와 같은 설정(KafkaErrorHandlingConfig)을 쓴다
@Component
@RequiredArgsConstructor
public class StatisticsConsumer {

    private final StatisticsService statisticsService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            id = "statistics",
            groupId = "statistics",
            topics = "${app.kafka.group-order-status-topic}",
            autoStartup = "${app.kafka.consumers-enabled}")
    public void on(ConsumerRecord<String, String> record) {
        // 헤더 eventId = 상태 변경 번호(outbox 줄 id). 알림 컨슈머와 같은 방법으로 읽는다
        Long eventId = GroupOrderStatusPublisher.eventIdOf(record);
        GroupOrderStatusPayload payload = objectMapper.readValue(record.value(), GroupOrderStatusPayload.class);
        statisticsService.record(eventId, payload);
    }
}
