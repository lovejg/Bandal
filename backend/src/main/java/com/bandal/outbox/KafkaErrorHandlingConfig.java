package com.bandal.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

import java.util.List;

// 컨슈머(알림, 통계)가 글 하나를 처리하다 예외가 났을 때 어떻게 할지
//
// 설정이 없을 때(기본)는 기다림 없이 10번 하고 로그 한 줄만 남긴 채 건너뛰었다(2026-10-27 JOURNAL)
// 깨진 글은 10번 헛돌다 사라지고, DB가 10초 끊기자 멀쩡한 글까지 버려졌다
//
// 바꾼 것:
// 1. 다시 할 때 기다리는 시간을 늘려 간다: 1초, 2초, 4초, 8초, 16초, 그다음부터 30초 간격. 쉰 시간이 모두 합쳐 5분이 되면 포기한다
//    실제로 포기하기까지는 5분보다 길다. DB가 꺼졌을 때는 13분쯤 걸렸다(아래 setMaxElapsedTime 주석)
//    다시 하는 동안 같은 칸의 뒤 글은 기다린다(같은 방의 순서를 지키려고)
// 2. 다시 해도 소용없는 예외(글 자체가 잘못됨)는 기다리지 않고 바로 포기한다
// 3. 포기한 글은 버리지 않고 DLT(원래 토픽 이름 + "-dlt", 같은 칸 번호)로 옮긴다. 옮긴 뒤 책갈피가 앞으로 가서 뒤 글이 처리된다
// 4. 옮길 때마다 카운터(bandal.kafka.dead.letter)를 올리고 로그를 남긴다. Grafana가 이 숫자로 경고한다
//
// Spring Boot는 이 빈(CommonErrorHandler)을 찾아 모든 @KafkaListener에 붙인다
@Slf4j
@Configuration
public class KafkaErrorHandlingConfig {

    // DLT 카운터를 다는 컨슈머 그룹들. NotificationConsumer, StatisticsConsumer의 groupId와 같다
    static final List<String> CONSUMER_GROUPS = List.of("notification", "statistics");

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate, MeterRegistry meterRegistry,
                                          @Value("${app.kafka.group-order-status-topic}") String topic) {
        // 카운터를 앱이 뜰 때 0으로 미리 만들어 둔다
        // 처음 DLT로 옮길 때 만들면 Prometheus에는 "없던 숫자가 1로 나타남"으로 보인다. 경고가 쓰는 increase()는 그걸 늘어난 것으로 세지 않아
        // 운영에서 생긴 첫 DLT에 경고가 안 울린다. 0부터 있으면 0 → 1이 늘어난 것으로 잡힌다
        for (String group : CONSUMER_GROUPS) {
            deadLetterCounter(meterRegistry, topic, group);
        }

        // 포기했을 때 할 일(recoverer): 글을 DLT로 옮긴다
        // 옮길 때 KafkaTemplate으로 원래 키, 값을 그대로 다시 보낸다. 헤더에는 Spring이 원래 토픽, 칸, 오프셋, 컨슈머 그룹,
        // 예외 이름과 메시지를 저절로 붙인다(kafka_dlt-로 시작하는 헤더). 사람이 원인을 볼 때 이 헤더를 본다
        DeadLetterPublishingRecoverer recoverer = new CountingDeadLetterRecoverer(kafkaTemplate, meterRegistry);

        // 다시 하기 전에 기다리는 시간(backoff). 처음 1초, 그다음은 앞의 두 배(2.0)씩 늘린다
        // 아무리 늘어나도 한 번에 30초를 넘게 기다리지는 않고, 쉰 시간의 합이 5분을 넘으면 그만 포기하고 recoverer를 부른다
        // 설정이 없을 때는 4.5초 만에 포기해서 10초짜리 DB 끊김도 못 넘겼다
        //
        // 주의: 이 5분은 "쉰 시간"만 더한 값이다. 시도 하나가 걸린 시간은 들어가지 않는다
        // DB 컨테이너가 꺼지면 시도마다 커넥션을 30초(Hikari connection-timeout) 기다리다 실패한다
        // 시도가 15번쯤이라 30초 × 15 ≈ 7분이 더해져, 리허설에서 처음 실패부터 DLT까지 약 13분이 걸렸다(2026-10-28)
        // 그동안 그 칸의 뒤 글도 멈춰 있지만, DB가 꺼진 동안에는 뒤 글도 어차피 처리할 수 없어 그대로 둔다
        ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxInterval(30_000);
        backOff.setMaxElapsedTime(5 * 60_000);

        // 실패한 글로 책갈피를 되돌려(seek) 같은 글을 다시 읽게 하고, 기다림이 다 떨어지면 recoverer를 부른 뒤 다음 글로 넘어간다
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);

        // 다시 해도 소용없는 예외. 여기 적은 예외(또는 그 예외가 원인으로 들어 있는 예외)가 나면 기다리지 않고 바로 DLT로 옮긴다
        // 고르는 기준: "같은 글을 다시 읽으면 결과가 달라질 수 있나". 달라질 수 없으면 여기 넣는다
        // 적지 않은 예외(DB 끊김 DataAccessResourceFailureException, 쿼리 시간 초과 QueryTimeoutException 등)는
        // "기다리면 풀릴 수도 있다"로 보고 위 간격대로 다시 한다
        //
        // 리스너에서 난 예외는 Spring이 ListenerExecutionFailedException으로 감싸서 넘긴다
        // 실패 처리는 감싼 예외의 원인(cause)을 따라 내려가며 이 목록과 맞춰 보므로, 안쪽 예외 이름을 그대로 적으면 된다
        // (ClassCastException, 메시지 변환 실패 같은 몇 가지는 Spring Kafka가 처음부터 이 목록에 넣어 둔다)
        handler.addNotRetryableExceptions(
                // 헤더 eventId가 없거나 숫자가 아님. 글을 넣은 쪽이 잘못 넣었다
                MalformedMessageException.class,
                // JSON이 깨졌거나 payload 모양과 다름(필드 타입이 틀림 등). Jackson 3의 모든 읽기 예외가 이걸 물려받는다
                JacksonException.class,
                // 코드 버그(payload에 꼭 있어야 할 칸이 비었는데 꺼내 씀 등). 코드를 고치기 전에는 몇 번을 해도 똑같이 터진다
                // 다시 하게 두면 5분 넘게 그 칸의 뒤 글이 전부 멈춘다. 바로 DLT로 보내 경고를 울리고, 고친 뒤 DLT에서 다시 넣는다(deploy/bandal/dlt-replay.sh)
                NullPointerException.class);
        return handler;
    }

    // DLT로 옮기면서 숫자를 세고 로그를 남긴다. 로그에 글 본문은 찍지 않는다(사용자 id, 금액이 들어 있다)
    static class CountingDeadLetterRecoverer extends DeadLetterPublishingRecoverer {

        private final MeterRegistry meterRegistry;

        CountingDeadLetterRecoverer(KafkaTemplate<String, String> kafkaTemplate, MeterRegistry meterRegistry) {
            super(kafkaTemplate);
            this.meterRegistry = meterRegistry;
        }

        @Override
        public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
            String group = consumer == null ? "unknown" : consumer.groupMetadata().groupId();
            String cause = rootCause(exception).getClass().getSimpleName();
            log.error("처리하지 못한 글을 DLT로 옮긴다 topic={} partition={} offset={} group={} cause={}",
                    record.topic(), record.partition(), record.offset(), group, cause);
            deadLetterCounter(meterRegistry, record.topic(), group).increment();
            super.accept(record, consumer, exception);
        }

        // 리스너의 예외는 Spring이 감싸서 던진다(ListenerExecutionFailedException). 로그에는 맨 안쪽 원인의 이름을 찍는다
        private static Throwable rootCause(Throwable t) {
            Throwable current = t;
            while (current.getCause() != null && current.getCause() != current) {
                current = current.getCause();
            }
            return current;
        }
    }

    // 같은 이름과 태그로 register를 다시 부르면 새로 만들지 않고 이미 있는 카운터를 돌려준다
    static Counter deadLetterCounter(MeterRegistry meterRegistry, String topic, String group) {
        return Counter.builder("bandal.kafka.dead.letter")
                .description("처리하지 못해 DLT로 옮긴 글 수")
                .tag("topic", topic)
                .tag("group", group)
                .register(meterRegistry);
    }
}
