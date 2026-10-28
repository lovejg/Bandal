package com.bandal.outbox;

import com.bandal.TestcontainersConfiguration;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.settlement.SettlementRepository;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

// 집배원이 방 상태 줄을 진짜 Kafka(Testcontainers)에 넣는가. 넣은 것을 테스트가 컨슈머로 직접 읽어 본다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "management.health.mail.enabled=false",
        "app.outbox.retry-base-seconds=30"
})
class OutboxRelayKafkaTest {

    static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    @Autowired
    OutboxRelay outboxRelay;

    @Autowired
    OutboxMessageRepository outboxMessageRepository;

    @Autowired
    ConsumerFactory<String, String> consumerFactory;

    @Value("${app.kafka.group-order-status-topic}")
    String topic;

    @Autowired
    UserRepository userRepository;

    @Autowired
    UniversityRepository universityRepository;

    @Autowired
    SettlementRepository settlementRepository;

    @Autowired
    OrderItemRepository orderItemRepository;

    @Autowired
    ParticipationRepository participationRepository;

    @Autowired
    GroupOrderRepository groupOrderRepository;

    @Autowired
    PickupSpotRepository pickupSpotRepository;

    @MockitoBean
    JavaMailSender mailSender;

    // 진짜로 Kafka에 넣되, 특정 줄에서만 실패를 끼워 넣으려고 감싼다(spy). 끼워 넣지 않은 호출은 진짜 메서드가 돈다
    @MockitoSpyBean
    GroupOrderStatusPublisher groupOrderStatusPublisher;

    ObjectMapper objectMapper = new ObjectMapper();

    Consumer<String, String> consumer;

    University hankuk;

    @BeforeEach
    void setUp() {
        outboxMessageRepository.deleteAll();
        settlementRepository.deleteAll();
        orderItemRepository.deleteAll();
        participationRepository.deleteAll();
        groupOrderRepository.deleteAll();
        userRepository.deleteAll();
        pickupSpotRepository.deleteAll();
        universityRepository.deleteAll();

        hankuk = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));

        // 테스트마다 새 그룹이라 책갈피가 없다. 맨 앞부터 읽게 한다. 다른 테스트가 넣은 글은 키(방 id)로 걸러 낸다
        Properties props = new Properties();
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = consumerFactory.createConsumer("test-" + UUID.randomUUID(), null, null, props);
        consumer.subscribe(List.of(topic));
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    // 이 방 id를 키로 가진 글을 count개 모일 때까지 읽는다(최대 15초)
    List<ConsumerRecord<String, String>> readFor(long groupOrderId, int count) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 15_000;
        while (found.size() < count && System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (String.valueOf(groupOrderId).equals(record.key())) {
                    found.add(record);
                }
            }
        }
        return found;
    }

    // 다른 테스트 실행과 겹치지 않게 방 id를 크게, 매번 다르게
    long roomId() {
        return 1_000_000L + (System.nanoTime() % 1_000_000L);
    }

    String payload(long groupOrderId, String to) {
        return "{\"groupOrderId\": " + groupOrderId + ", \"to\": \"" + to + "\"}";
    }

    OutboxMessage queuedStatus(long groupOrderId, String to, Instant at) {
        return outboxMessageRepository.save(OutboxMessage.groupOrderStatus(groupOrderId, payload(groupOrderId, to), at));
    }

    OutboxMessage reload(OutboxMessage message) {
        return outboxMessageRepository.findById(message.getId()).orElseThrow();
    }

    @Test
    @DisplayName("방 상태 줄은 키를 방 id로 해 Kafka에 넣고, 보냄으로 적는다")
    void publishesToKafka() {
        long roomId = roomId();
        OutboxMessage message = queuedStatus(roomId, "CLOSED", NOW.minusSeconds(10));

        outboxRelay.runAt(NOW);

        assertThat(reload(message).getStatus()).isEqualTo(OutboxStatus.SENT);
        List<ConsumerRecord<String, String>> records = readFor(roomId, 1);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).key()).isEqualTo(String.valueOf(roomId));
        // 헤더의 상태 변경 번호는 outbox 줄 id
        Header eventId = records.get(0).headers().lastHeader(GroupOrderStatusPublisher.EVENT_ID_HEADER);
        assertThat(new String(eventId.value(), StandardCharsets.UTF_8)).isEqualTo(String.valueOf(message.getId()));
        // 내용은 jsonb에서 꺼낸 것이라 글자 순서가 다를 수 있다. JSON으로 비교한다
        assertThat(objectMapper.readTree(records.get(0).value()))
                .isEqualTo(objectMapper.readTree(payload(roomId, "CLOSED")));
    }

    @Test
    @DisplayName("같은 방의 줄 둘은 같은 칸에, 넣은 차례대로 들어간다")
    void sameRoomSamePartitionInOrder() {
        long roomId = roomId();
        queuedStatus(roomId, "CLOSED", NOW.minusSeconds(20));
        queuedStatus(roomId, "SETTLING", NOW.minusSeconds(10));

        // 찜할 때 앞 줄이 아직 PENDING이라 한 차례에 같은 방은 한 줄만 나간다. 다음 차례에 뒤 줄
        outboxRelay.runAt(NOW);
        outboxRelay.runAt(NOW.plusSeconds(5));

        List<ConsumerRecord<String, String>> records = readFor(roomId, 2);
        assertThat(records).hasSize(2);
        assertThat(records.get(0).partition()).isEqualTo(records.get(1).partition());
        assertThat(records.get(0).offset()).isLessThan(records.get(1).offset());
        assertThat(objectMapper.readTree(records.get(0).value()).get("to").asString()).isEqualTo("CLOSED");
        assertThat(objectMapper.readTree(records.get(1).value()).get("to").asString()).isEqualTo("SETTLING");
    }

    @Test
    @DisplayName("메일 줄과 방 상태 줄이 한 번에 찜돼도 둘 다 제 길로 나간다")
    void mixedBatch() {
        User lee = userRepository.save(new User(hankuk, "lee@hankuk.ac.kr", "hashed-password", "마라탕러버"));
        OutboxMessage mail = outboxMessageRepository.save(
                OutboxMessage.mail(OutboxType.VERIFICATION_MAIL, lee.getId(), NOW.minusSeconds(20)));
        long roomId = roomId();
        OutboxMessage status = queuedStatus(roomId, "CLOSED", NOW.minusSeconds(10));

        outboxRelay.runAt(NOW);

        verify(mailSender).send(any(SimpleMailMessage.class));
        assertThat(reload(mail).getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(reload(status).getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(readFor(roomId, 1)).hasSize(1);
    }

    // 이 방의 CLOSED 줄을 넣을 때만 Kafka가 응답이 없는 것처럼 실패시킨다. once면 한 번만, 아니면 계속
    void failClosedOf(long roomId, boolean once) {
        IllegalStateException down = new IllegalStateException("Kafka 응답 없음(테스트)");
        if (once) {
            doThrow(down).doCallRealMethod()
                    .when(groupOrderStatusPublisher).publish(anyLong(), eq(roomId), argThat(p -> p.contains("\"CLOSED\"")));
        } else {
            doThrow(down)
                    .when(groupOrderStatusPublisher).publish(anyLong(), eq(roomId), argThat(p -> p.contains("\"CLOSED\"")));
        }
    }

    List<String> tosOf(List<ConsumerRecord<String, String>> records) {
        return records.stream().map(r -> objectMapper.readTree(r.value()).get("to").asString()).toList();
    }

    @Test
    @DisplayName("앞 줄이 실패해 다시 기다리는 동안 생긴 같은 방의 뒤 줄은 앞 줄보다 먼저 나가지 않는다")
    void laterRowWaitsForFailedEarlierRow() {
        long roomId = roomId();
        failClosedOf(roomId, true);
        OutboxMessage closed = queuedStatus(roomId, "CLOSED", NOW.minusSeconds(10));

        outboxRelay.runAt(NOW); // CLOSED 실패. 30초 뒤에 다시

        OutboxMessage settling = queuedStatus(roomId, "SETTLING", NOW.plusSeconds(5));
        outboxRelay.runAt(NOW.plusSeconds(10)); // CLOSED는 아직 다시 볼 때가 아니다
        outboxRelay.runAt(NOW.plusSeconds(40)); // CLOSED 다시 시도
        outboxRelay.runAt(NOW.plusSeconds(45));

        assertThat(reload(closed).getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(reload(settling).getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(tosOf(readFor(roomId, 2))).containsExactly("CLOSED", "SETTLING");
    }

    @Test
    @DisplayName("같은 방의 줄 둘이 한 차례에 찜될 때 앞 줄이 실패하면 뒤 줄도 먼저 나가지 않는다")
    void sameBatchLaterRowWaits() {
        long roomId = roomId();
        failClosedOf(roomId, true);
        queuedStatus(roomId, "CLOSED", NOW.minusSeconds(20));
        queuedStatus(roomId, "SETTLING", NOW.minusSeconds(10));

        outboxRelay.runAt(NOW); // 둘 다 보낼 때가 됐다. CLOSED 실패
        outboxRelay.runAt(NOW.plusSeconds(40));
        outboxRelay.runAt(NOW.plusSeconds(45));

        assertThat(tosOf(readFor(roomId, 2))).containsExactly("CLOSED", "SETTLING");
    }

    @Test
    @DisplayName("한 방의 줄이 막혀 있어도 다른 방의 줄과 메일 줄은 그대로 나간다")
    void otherRoomsAndMailNotBlocked() {
        long stuckRoom = roomId();
        long otherRoom = stuckRoom + 1;
        failClosedOf(stuckRoom, false);
        queuedStatus(stuckRoom, "CLOSED", NOW.minusSeconds(30));
        OutboxMessage stuckLater = queuedStatus(stuckRoom, "SETTLING", NOW.minusSeconds(20));
        OutboxMessage other = queuedStatus(otherRoom, "CLOSED", NOW.minusSeconds(10));
        User lee = userRepository.save(new User(hankuk, "lee@hankuk.ac.kr", "hashed-password", "마라탕러버"));
        OutboxMessage mail = outboxMessageRepository.save(
                OutboxMessage.mail(OutboxType.VERIFICATION_MAIL, lee.getId(), NOW.minusSeconds(5)));

        outboxRelay.runAt(NOW);

        assertThat(reload(stuckLater).getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(reload(other).getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(reload(mail).getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(readFor(otherRoom, 1)).hasSize(1);
    }

    @Test
    @DisplayName("앞 줄이 재시도를 다 써서 실패로 끝나면 뒤 줄은 나간다")
    void givenUpRowDoesNotBlockForever() {
        long roomId = roomId();
        failClosedOf(roomId, false);
        OutboxMessage closed = queuedStatus(roomId, "CLOSED", NOW.minusSeconds(10));
        OutboxMessage settling = queuedStatus(roomId, "SETTLING", NOW.minusSeconds(5));

        // 한 시간씩 띄워 돌리면 매번 다시 볼 때가 지나 있다. MAX_ATTEMPTS번 실패하면 FAILED, 그다음 차례에 뒤 줄이 나간다
        for (int i = 0; i <= OutboxMessage.MAX_ATTEMPTS; i++) {
            outboxRelay.runAt(NOW.plus(Duration.ofHours(i)));
        }

        assertThat(reload(closed).getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(reload(settling).getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(tosOf(readFor(roomId, 1))).containsExactly("SETTLING");
    }
}
