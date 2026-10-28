package com.bandal.notification;

import com.bandal.TestcontainersConfiguration;
import com.bandal.grouporder.GroupOrderStatus;
import com.bandal.grouporder.StatusChangedBy;
import com.bandal.outbox.GroupOrderStatusPayload;
import com.bandal.outbox.GroupOrderStatusPayload.Participant;
import com.bandal.outbox.GroupOrderStatusPublisher;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

// 처리하지 못하는 글을 만났을 때 컨슈머가 어떻게 하나. 진짜 Kafka(Testcontainers)로 본다
// - 깨진 글(영구 실패): 다시 하지 않고 DLT로 옮긴다. 같은 칸 뒤의 정상 글은 처리된다
// - DB가 잠깐 끊김(일시 실패): 기다렸다 다시 해서 알림을 잃지 않는다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "app.kafka.consumers-enabled=true",
        "app.kafka.group-order-status-topic=group-order-status-failure-test"
})
class NotificationFailureKafkaTest {

    // DB가 끊긴 흉내를 내려고 진짜 서비스를 감싼다. 평소에는 진짜 메서드를 그대로 부른다
    @MockitoSpyBean
    NotificationService notificationService;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    ConsumerFactory<String, String> consumerFactory;

    @Autowired
    NotificationRepository notificationRepository;

    @Autowired
    ObjectMapper objectMapper;

    @Value("${app.kafka.group-order-status-topic}")
    String topic;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
    }

    // 키는 방 id. 같은 방이면 같은 칸에 줄 선다
    void sendRaw(long roomId, Long eventId, String value) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, String.valueOf(roomId), value);
        if (eventId != null) {
            record.headers().add(GroupOrderStatusPublisher.EVENT_ID_HEADER, String.valueOf(eventId).getBytes(StandardCharsets.UTF_8));
        }
        kafkaTemplate.send(record).get();
    }

    // 방 roomId의 마감. 방장(1) 말고 참여자 userId 한 명이 알림을 받는다
    String closed(long roomId, long userId) throws Exception {
        return objectMapper.writeValueAsString(new GroupOrderStatusPayload(roomId, 10L, "마라탕집", 1L,
                GroupOrderStatus.RECRUITING, GroupOrderStatus.CLOSED, StatusChangedBy.HOST, null, null, null,
                List.of(new Participant(1L, null), new Participant(userId, null)), Instant.parse("2026-10-27T10:00:00Z")));
    }

    boolean waitForNotification(long eventId, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (notificationRepository.findAll().stream().anyMatch(n -> n.getEventId() == eventId)) return true;
            Thread.sleep(200);
        }
        return false;
    }

    // DLT에서 값이 value인 글을 기다린다. 못 찾으면 빈 목록
    List<ConsumerRecord<String, String>> waitForDeadLetter(String value, Duration timeout) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("dlt-reader-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(topic + "-dlt"));
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (found.isEmpty() && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (value.equals(r.value())) found.add(r);
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("JSON이 깨진 글은 다시 하지 않고 곧바로 DLT로 옮겨지고, 같은 칸 뒤의 정상 글은 알림이 된다")
    void brokenJsonGoesToDeadLetter() throws Exception {
        String broken = "{\"groupOrderId\": 1, 괄호가 빠졌다";
        sendRaw(1L, 900L, broken);
        sendRaw(1L, 901L, closed(1L, 2L));

        // 다시 했다면(1초, 2초, 4초…) 몇 초 안에 DLT에 닿지 못한다
        assertThat(waitForDeadLetter(broken, Duration.ofSeconds(5))).as("DLT에 옮겨진 깨진 글").isNotEmpty();
        assertThat(waitForNotification(901L, Duration.ofSeconds(10))).as("뒤의 정상 글로 만든 알림").isTrue();
    }

    @Test
    @DisplayName("eventId 헤더가 없는 글도 곧바로 DLT로 옮겨진다")
    void missingHeaderGoesToDeadLetter() throws Exception {
        String noHeader = closed(3L, 4L);
        sendRaw(3L, null, noHeader);

        assertThat(waitForDeadLetter(noHeader, Duration.ofSeconds(5))).as("DLT에 옮겨진 헤더 없는 글").isNotEmpty();
    }

    @Test
    @DisplayName("DB가 10초 끊겨도 기다렸다 다시 해서 알림을 잃지 않는다")
    void survivesShortDatabaseOutage() throws Exception {
        AtomicLong outageStartedAt = new AtomicLong();
        AtomicInteger attempts = new AtomicInteger();
        // 처음 부른 때부터 10초 동안은 DB가 끊긴 것처럼 예외를 던진다
        doAnswer(invocation -> {
            attempts.incrementAndGet();
            outageStartedAt.compareAndSet(0, System.currentTimeMillis());
            if (System.currentTimeMillis() - outageStartedAt.get() < 10_000) {
                throw new DataAccessResourceFailureException("DB 연결이 끊겼다(흉내)");
            }
            return invocation.callRealMethod();
        }).when(notificationService).record(any(), any());

        sendRaw(5L, 950L, closed(5L, 6L));

        boolean recorded = waitForNotification(950L, Duration.ofSeconds(40));
        assertThat(recorded).as("끊김이 풀린 뒤 만든 알림 (시도 %d번)", attempts.get()).isTrue();
    }
}
