package com.bandal.notification;

import com.bandal.outbox.GroupOrderStatusPayload;
import com.bandal.outbox.GroupOrderStatusPublisher;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

// 알림 컨슈머. 방 상태 토픽(group-order-status)의 글을 하나씩 받아 알림 줄로 바꾼다
//
// 글이 여기까지 오는 길:
//   방 상태가 바뀜(엔티티가 기록) → 커밋 직전 리스너가 outbox에 한 줄 → 집배원(OutboxRelay)이 Kafka에 붙임 → 여기서 읽음
//
// 컨슈머 그룹은 notification. 통계 컨슈머는 다른 그룹이라 같은 글을 따로, 자기 책갈피(offset)로 읽는다
// 알림이 밀려도 통계는 상관없이 자기 속도로 간다
//
// 메서드가 예외 없이 끝나야 이 글의 책갈피가 앞으로 간다. 예외가 나면 같은 글을 다시 받는다
// 그래서 같은 글을 두 번 받아도 알림이 한 벌만 남게 서비스가 (eventId, 받는 사람)으로 거른다
//
// autoStartup이 설정(app.kafka.consumers-enabled)을 따른다
// 백엔드 컨테이너는 false라 이 컨슈머가 안 돌고, worker 컨테이너만 true로 켠다
// 같은 이미지를 쓰지만 컨슈머가 메모리나 스레드를 먹어도 웹 요청을 받는 백엔드는 흔들리지 않는다
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    // id: 리스너 이름(테스트나 운영에서 이 리스너를 멈추고 켤 때 부르는 이름)
    // groupId: 컨슈머 그룹. Kafka가 그룹마다 "어디까지 읽었나"를 따로 기억한다
    @KafkaListener(
            id = "notification",
            groupId = "notification",
            topics = "${app.kafka.group-order-status-topic}",
            autoStartup = "${app.kafka.consumers-enabled}")
    public void on(ConsumerRecord<String, String> record) {
        // 헤더 eventId = 상태 변경 번호(outbox 줄 id). 본문(payload)이 아니라 헤더에 있는 이유:
        // 본문은 outbox 줄을 저장하기 전에 만들어서 그때는 줄 id가 아직 없다. 집배원이 붙일 때 헤더로 단다
        // 헤더가 없거나 숫자가 아니면 MalformedMessageException(다시 하지 않고 바로 DLT로 가는 예외)
        Long eventId = GroupOrderStatusPublisher.eventIdOf(record);
        // 본문은 JSON 문자열이다. 방, 가게 이름, 바뀐 상태, 참여자별 금액 같은 "그 순간의 사실"이 다 들어 있어서
        // 본 서비스 DB를 다시 조회하지 않는다. 늦게 읽어도, 되감아 다시 읽어도 같은 알림이 나온다
        // JSON이 깨졌으면 여기서 JacksonException이 난다
        // 예외가 나면 실패 처리(KafkaErrorHandlingConfig)가 다시 할지, DLT로 옮길지 정한다
        GroupOrderStatusPayload payload = objectMapper.readValue(record.value(), GroupOrderStatusPayload.class);
        notificationService.record(eventId, payload);
    }
}
