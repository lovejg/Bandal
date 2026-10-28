package com.bandal.notification;

import com.bandal.TestcontainersConfiguration;
import com.bandal.grouporder.GroupOrderStatus;
import com.bandal.grouporder.StatusChangedBy;
import com.bandal.outbox.GroupOrderStatusPayload;
import com.bandal.outbox.GroupOrderStatusPayload.Participant;
import com.bandal.outbox.GroupOrderStatusPublisher;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 진짜 Kafka(Testcontainers)에 글을 넣으면 알림 컨슈머가 받아 알림 행을 만드는가
// 다른 테스트가 넣는 글과 섞이지 않게 이 테스트만 쓰는 토픽을 쓴다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "app.kafka.consumers-enabled=true",
        "app.kafka.group-order-status-topic=group-order-status-notification-test"
})
class NotificationConsumerTest {

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

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

    // 집배원이 넣는 모양 그대로: 키는 방 id, 헤더에 상태 변경 번호
    void send(long eventId, GroupOrderStatusPayload payload) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic,
                String.valueOf(payload.groupOrderId()), objectMapper.writeValueAsString(payload));
        record.headers().add(GroupOrderStatusPublisher.EVENT_ID_HEADER, String.valueOf(eventId).getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get();
    }

    // 컨슈머는 따로 도는 스레드라 행이 생길 때까지 기다린다(최대 15초)
    List<Notification> waitFor(int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        List<Notification> found = notificationRepository.findAll();
        while (found.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            found = notificationRepository.findAll();
        }
        return found;
    }

    @Test
    @DisplayName("방장이 정산을 시작한 글을 받으면 방장 말고 참여자마다 알림이 생긴다")
    void consumesAndRecords() throws Exception {
        send(500L, new GroupOrderStatusPayload(7L, 10L, "마라탕집", 1L,
                GroupOrderStatus.CLOSED, GroupOrderStatus.SETTLING, StatusChangedBy.HOST, null, null, 3_000L,
                List.of(new Participant(1L, 13_000L), new Participant(2L, 9_000L), new Participant(3L, 11_000L)),
                Instant.parse("2026-10-26T10:00:00Z")));

        List<Notification> found = waitFor(2);

        assertThat(found).extracting(Notification::getUserId).containsExactlyInAnyOrder(2L, 3L);
        assertThat(found).extracting(Notification::getEventId).containsOnly(500L);
    }
}
