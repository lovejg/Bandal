package com.bandal.notification;

import com.bandal.TestcontainersConfiguration;
import com.bandal.grouporder.CancelType;
import com.bandal.grouporder.GroupOrderChangeKind;
import com.bandal.grouporder.GroupOrderStatus;
import com.bandal.grouporder.StatusChangedBy;
import com.bandal.outbox.GroupOrderStatusPayload;
import com.bandal.outbox.GroupOrderStatusPayload.Participant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// 상태 변경 글 하나가 받는 사람마다 알림 한 줄이 되는가. Kafka 없이 서비스만 부른다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NotificationServiceTest {

    static final Long ROOM_ID = 7L;
    static final Long HOST = 1L;
    static final Long LEE = 2L;
    static final Long PARK = 3L;
    static final Instant AT = Instant.parse("2026-10-26T10:00:00Z").truncatedTo(ChronoUnit.MICROS);

    @Autowired
    NotificationService notificationService;

    @Autowired
    NotificationRepository notificationRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
    }

    GroupOrderStatusPayload payload(GroupOrderStatus to, StatusChangedBy by, String cancelReason, List<Participant> participants) {
        return new GroupOrderStatusPayload(ROOM_ID, 10L, "마라탕집", HOST,
                GroupOrderStatus.CLOSED, to, by,
                cancelReason == null ? null : CancelType.HOST_WHILE_CLOSED, cancelReason,
                3_000L, participants, AT);
    }

    // 정산 시작: 사람마다 보낼 돈이 다르다
    GroupOrderStatusPayload settlingByHost() {
        return payload(GroupOrderStatus.SETTLING, StatusChangedBy.HOST, null, List.of(
                new Participant(HOST, 13_000L), new Participant(LEE, 9_000L), new Participant(PARK, 11_000L)));
    }

    List<Long> recipients() {
        return notificationRepository.findAll().stream().map(Notification::getUserId).sorted().toList();
    }

    @Test
    @DisplayName("방장이 바꾼 일은 방장을 빼고 참여자에게만 알린다")
    void hostChangeSkipsHost() {
        notificationService.record(100L, settlingByHost());

        assertThat(recipients()).containsExactly(LEE, PARK);
    }

    @Test
    @DisplayName("스케줄러가 바꾼 일은 방장에게도 알린다")
    void systemChangeIncludesHost() {
        notificationService.record(100L, payload(GroupOrderStatus.CANCELED, StatusChangedBy.SYSTEM, "방장이 3시간 동안 배달비를 입력하지 않았습니다",
                List.of(new Participant(HOST, null), new Participant(LEE, null))));

        assertThat(recipients()).containsExactly(HOST, LEE);
    }

    @Test
    @DisplayName("알림에는 상태 변경 번호, 방, 가게 이름, 바뀐 상태, 그 사람의 금액, 바뀐 시각이 들어간다. 안 읽음으로 시작한다")
    void copiesFacts() {
        notificationService.record(100L, settlingByHost());

        Notification lee = notificationRepository.findAll().stream()
                .filter(n -> n.getUserId().equals(LEE)).findFirst().orElseThrow();
        assertThat(lee.getEventId()).isEqualTo(100L);
        assertThat(lee.getGroupOrderId()).isEqualTo(ROOM_ID);
        assertThat(lee.getStoreName()).isEqualTo("마라탕집");
        assertThat(lee.getStatus()).isEqualTo(GroupOrderStatus.SETTLING);
        assertThat(lee.getAmount()).isEqualTo(9_000L);
        assertThat(lee.getCancelReason()).isNull();
        assertThat(lee.getOccurredAt()).isEqualTo(AT);
        assertThat(lee.getReadAt()).isNull();
    }

    @Test
    @DisplayName("취소면 사유가 들어간다")
    void cancelReason() {
        notificationService.record(100L, payload(GroupOrderStatus.CANCELED, StatusChangedBy.HOST, "가게가 문을 닫았어요",
                List.of(new Participant(HOST, null), new Participant(LEE, null))));

        assertThat(notificationRepository.findAll()).singleElement()
                .extracting(Notification::getCancelReason).isEqualTo("가게가 문을 닫았어요");
    }

    @Test
    @DisplayName("같은 글을 두 번 받아도 예외 없이 끝나고 알림은 한 벌만 남는다")
    void sameEventTwice() {
        notificationService.record(100L, settlingByHost());

        // 처리 뒤 책갈피를 커밋하기 전에 worker가 죽어 같은 글을 다시 받았다
        assertThatCode(() -> notificationService.record(100L, settlingByHost())).doesNotThrowAnyException();

        assertThat(recipients()).containsExactly(LEE, PARK);
    }

    @Test
    @DisplayName("배달비 수정 글을 받으면 방장 말고 참여자마다 종류, 바뀐 배달비, 새 금액이 담긴 알림이 생긴다")
    void deliveryFeeChanged() {
        notificationService.record(200L, new GroupOrderStatusPayload(ROOM_ID, 10L, "마라탕집", HOST,
                GroupOrderStatus.SETTLING, GroupOrderStatus.SETTLING, StatusChangedBy.HOST, GroupOrderChangeKind.DELIVERY_FEE_CHANGED,
                null, null, 4_000L, null,
                List.of(new Participant(HOST, 13_500L), new Participant(LEE, 9_500L), new Participant(PARK, 11_500L)), AT));

        assertThat(recipients()).containsExactly(LEE, PARK);
        Notification lee = notificationRepository.findAll().stream()
                .filter(n -> n.getUserId().equals(LEE)).findFirst().orElseThrow();
        assertThat(lee.getKind()).isEqualTo(GroupOrderChangeKind.DELIVERY_FEE_CHANGED);
        assertThat(lee.getStatus()).isEqualTo(GroupOrderStatus.SETTLING);
        assertThat(lee.getDeliveryFee()).isEqualTo(4_000L);
        assertThat(lee.getAmount()).isEqualTo(9_500L);
    }

    @Test
    @DisplayName("도착 예정 수정 글을 받으면 참여자마다 종류와 바뀐 도착 예정 시각이 담긴 알림이 생긴다")
    void arrivalChanged() {
        Instant arrival = AT.plusSeconds(3600);
        notificationService.record(300L, new GroupOrderStatusPayload(ROOM_ID, 10L, "마라탕집", HOST,
                GroupOrderStatus.ORDERED, GroupOrderStatus.ORDERED, StatusChangedBy.HOST, GroupOrderChangeKind.ARRIVAL_CHANGED,
                null, null, 3_000L, arrival,
                List.of(new Participant(HOST, 13_000L), new Participant(LEE, 9_000L)), AT));

        Notification lee = notificationRepository.findAll().stream().filter(n -> n.getUserId().equals(LEE))
                .findFirst().orElseThrow();
        assertThat(recipients()).containsExactly(LEE);
        assertThat(lee.getKind()).isEqualTo(GroupOrderChangeKind.ARRIVAL_CHANGED);
        assertThat(lee.getStatus()).isEqualTo(GroupOrderStatus.ORDERED);
        assertThat(lee.getExpectedArrivalAt()).isEqualTo(arrival);
    }

    @Test
    @DisplayName("상태 변경 알림의 종류는 상태 변경이고, 그때의 배달비가 담긴다")
    void statusChangeKind() {
        notificationService.record(100L, settlingByHost());

        Notification lee = notificationRepository.findAll().stream()
                .filter(n -> n.getUserId().equals(LEE)).findFirst().orElseThrow();
        assertThat(lee.getKind()).isEqualTo(GroupOrderChangeKind.STATUS_CHANGED);
        assertThat(lee.getDeliveryFee()).isEqualTo(3_000L);
    }

    @Test
    @DisplayName("같은 방이라도 상태 변경이 다르면(번호가 다르면) 따로 알린다")
    void differentEventsSameRoom() {
        notificationService.record(100L, payload(GroupOrderStatus.CLOSED, StatusChangedBy.SYSTEM, null,
                List.of(new Participant(HOST, null), new Participant(LEE, null))));
        notificationService.record(101L, settlingByHost());

        assertThat(notificationRepository.count()).isEqualTo(4); // 마감 2(방장, 이) + 정산 시작 2(이, 박)
    }
}
