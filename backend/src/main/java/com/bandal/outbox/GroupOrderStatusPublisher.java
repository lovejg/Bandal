package com.bandal.outbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// 방 상태 줄 하나를 Kafka 토픽에 넣는다. 집배원이 부른다
@Component
public class GroupOrderStatusPublisher {

    // 글 헤더에 다는 상태 변경 번호(outbox 줄 id). 컨슈머가 같은 글을 두 번 읽었는지 이걸로 가린다
    public static final String EVENT_ID_HEADER = "eventId";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public GroupOrderStatusPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                     @Value("${app.kafka.group-order-status-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    // 키는 방 id. 같은 방은 늘 같은 칸에 들어가 넣은 순서대로 읽힌다
    // 헤더는 내용(payload) 옆에 붙는 꼬리표다. payload는 outbox 줄 id가 생기기 전에 만들어져서 번호를 안에 넣을 수 없다
    // send는 기다리지 않고 바로 돌아온다(나중에 결과가 오는 Future). 결과를 기다리지 않으면 브로커가 거절해도 집배원은 "보냄"으로 적는다.
    // 그래서 브로커가 받았다고 답할 때까지 기다리고, 실패면 예외를 던져 집배원이 재시도로 돌리게 한다
    public void publish(Long eventId, Long groupOrderId, String payload) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, String.valueOf(groupOrderId), payload);
        record.headers().add(EVENT_ID_HEADER, String.valueOf(eventId).getBytes(StandardCharsets.UTF_8));
        try {
            kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka 전송을 기다리다 중단됐습니다", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka에 넣지 못했습니다. groupOrderId=" + groupOrderId, e);
        }
    }

    // 받는 쪽(알림, 통계 컨슈머)이 헤더에서 상태 변경 번호를 꺼낸다. 넣는 쪽과 같은 곳에 두어 헤더 이름과 모양이 한곳에서 정해진다
    // 헤더가 없거나 숫자가 아니면 글 자체가 잘못된 것이라 MalformedMessageException을 던진다(다시 해도 소용없음 → 바로 DLT)
    // 그냥 두면 NullPointerException이 나서, 실패 처리가 이게 "글이 잘못됐다"인지 "코드 버그"인지 가릴 수 없다
    public static Long eventIdOf(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(EVENT_ID_HEADER);
        if (header == null) {
            throw new MalformedMessageException("eventId 헤더가 없습니다");
        }
        try {
            return Long.valueOf(new String(header.value(), StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            throw new MalformedMessageException("eventId 헤더가 숫자가 아닙니다");
        }
    }
}
