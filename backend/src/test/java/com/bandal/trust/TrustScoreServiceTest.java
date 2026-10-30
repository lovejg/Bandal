package com.bandal.trust;

import com.bandal.TestcontainersConfiguration;
import com.bandal.grouporder.CancelType;
import com.bandal.grouporder.GroupOrderChangeKind;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.grouporder.GroupOrderStatus;
import com.bandal.grouporder.StatusChangedBy;
import com.bandal.outbox.GroupOrderStatusPayload;
import com.bandal.outbox.GroupOrderStatusPayload.Participant;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.settlement.SettlementRepository;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

// 방 상태 글 하나가 누구의 점수를 몇 점 움직이는지. 컨슈머를 거치지 않고 서비스에 글을 바로 넣는다(알림 서비스 테스트와 같은 방식)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TrustScoreServiceTest {

    static final Instant AT = Instant.parse("2026-11-01T12:00:00Z");
    static final AtomicLong EVENT = new AtomicLong(1_000);

    @Autowired
    TrustScoreService trustScoreService;

    @Autowired
    TrustScoreRepository trustScoreRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    UniversityRepository universityRepository;

    @Autowired
    PickupSpotRepository pickupSpotRepository;

    @Autowired
    GroupOrderRepository groupOrderRepository;

    @Autowired
    ParticipationRepository participationRepository;

    @Autowired
    OrderItemRepository orderItemRepository;

    @Autowired
    SettlementRepository settlementRepository;

    User host;
    User lee;
    User park;

    @BeforeEach
    void setUp() {
        trustScoreRepository.deleteAll();
        settlementRepository.deleteAll();
        orderItemRepository.deleteAll();
        participationRepository.deleteAll();
        groupOrderRepository.deleteAll();
        userRepository.deleteAll();
        pickupSpotRepository.deleteAll();
        universityRepository.deleteAll();

        University hankuk = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        host = userRepository.save(new User(hankuk, "host@hankuk.ac.kr", "hash", "방장님"));
        lee = userRepository.save(new User(hankuk, "lee@hankuk.ac.kr", "hash", "마라탕러버"));
        park = userRepository.save(new User(hankuk, "park@hankuk.ac.kr", "hash", "떡볶이장인"));
    }

    GroupOrderStatusPayload ended(GroupOrderStatus to, CancelType cancelType, List<Participant> participants) {
        return new GroupOrderStatusPayload(77L, 1L, "○○마라탕", host.getId(), GroupOrderStatus.SETTLING, to,
                StatusChangedBy.SYSTEM, cancelType, null, 3000L, participants, AT);
    }

    void record(GroupOrderStatusPayload payload) {
        trustScoreService.record(EVENT.incrementAndGet(), payload);
    }

    int scoreOf(User user) {
        return userRepository.findById(user.getId()).orElseThrow().getTrustScore();
    }

    @Test
    @DisplayName("배달완료면 방장과 참여자 모두 +2")
    void deliveredRaisesEveryone() {
        record(ended(GroupOrderStatus.DELIVERED, null,
                List.of(new Participant(host.getId(), 5000L, true), new Participant(lee.getId(), 9000L, true))));

        assertThat(scoreOf(host)).isEqualTo(52);
        assertThat(scoreOf(lee)).isEqualTo(52);
        assertThat(scoreOf(park)).isEqualTo(50);
    }

    @Test
    @DisplayName("같은 글을 두 번 받아도 한 번만 반영된다")
    void sameEventOnce() {
        GroupOrderStatusPayload payload = ended(GroupOrderStatus.DELIVERED, null, List.of(new Participant(lee.getId(), 9000L, true)));

        trustScoreService.record(5L, payload);
        trustScoreService.record(5L, payload);

        assertThat(scoreOf(lee)).isEqualTo(52);
        assertThat(trustScoreRepository.findTop20ByUserIdOrderByIdDesc(lee.getId())).hasSize(1);
    }

    @Test
    @DisplayName("방장이 정산중에 취소하면 방장 -15, 참여자는 그대로")
    void hostCanceledWhileSettling() {
        record(ended(GroupOrderStatus.CANCELED, CancelType.HOST_WHILE_SETTLING,
                List.of(new Participant(host.getId(), 5000L, true), new Participant(lee.getId(), 9000L, true))));

        assertThat(scoreOf(host)).isEqualTo(35);
        assertThat(scoreOf(lee)).isEqualTo(50);
    }

    @Test
    @DisplayName("방장이 마감 뒤 취소하거나 배달비를 안 넣어 자동 취소되면 방장 -5")
    void hostCanceledAfterClose() {
        record(ended(GroupOrderStatus.CANCELED, CancelType.HOST_WHILE_CLOSED, List.of(new Participant(host.getId(), null))));
        record(ended(GroupOrderStatus.CANCELED, CancelType.CLOSED_TIMEOUT, List.of(new Participant(host.getId(), null))));

        assertThat(scoreOf(host)).isEqualTo(40);
    }

    @Test
    @DisplayName("모집중 취소와 인원 미달 자동 취소는 아무도 깎지 않는다")
    void harmlessCancels() {
        record(ended(GroupOrderStatus.CANCELED, CancelType.HOST_WHILE_RECRUITING, List.of(new Participant(host.getId(), null))));
        record(ended(GroupOrderStatus.CANCELED, CancelType.DEADLINE_UNMET, List.of(new Participant(host.getId(), null))));

        assertThat(scoreOf(host)).isEqualTo(50);
        assertThat(trustScoreRepository.count()).isZero();
    }

    @Test
    @DisplayName("정산 시간 초과: 안 보낸 참여자가 있으면 그 사람만 -5, 방장은 그대로(주문할 수 없었다)")
    void settlingTimeoutBlamesUnpaid() {
        record(ended(GroupOrderStatus.CANCELED, CancelType.SETTLING_TIMEOUT, List.of(
                new Participant(host.getId(), 5000L, true),
                new Participant(lee.getId(), 9000L, true),
                new Participant(park.getId(), 7000L, false))));

        assertThat(scoreOf(park)).isEqualTo(45);
        assertThat(scoreOf(lee)).isEqualTo(50);
        assertThat(scoreOf(host)).isEqualTo(50);
    }

    @Test
    @DisplayName("정산 시간 초과: 모두 보냈는데 시간이 지났으면 방장이 주문하지 않은 것이라 방장 -15")
    void settlingTimeoutBlamesHostWhenAllPaid() {
        record(ended(GroupOrderStatus.CANCELED, CancelType.SETTLING_TIMEOUT, List.of(
                new Participant(host.getId(), 5000L, true),
                new Participant(lee.getId(), 9000L, true))));

        assertThat(scoreOf(host)).isEqualTo(35);
        assertThat(scoreOf(lee)).isEqualTo(50);
    }

    @Test
    @DisplayName("보냈는지 모르는 옛 글(paid 없음)이면 참여자를 깎지 않는다")
    void unknownPaidIsNotPunished() {
        record(ended(GroupOrderStatus.CANCELED, CancelType.SETTLING_TIMEOUT, List.of(
                new Participant(host.getId(), 5000L), new Participant(lee.getId(), 9000L))));

        assertThat(scoreOf(lee)).isEqualTo(50);
    }

    @Test
    @DisplayName("점수는 0 밑으로, 100 위로 가지 않는다")
    void clamped() {
        ReflectionTestUtils.setField(lee, "trustScore", 1);
        ReflectionTestUtils.setField(park, "trustScore", 99);
        userRepository.saveAll(List.of(lee, park));

        record(ended(GroupOrderStatus.CANCELED, CancelType.SETTLING_TIMEOUT, List.of(
                new Participant(host.getId(), 5000L, true), new Participant(lee.getId(), 9000L, false))));
        record(ended(GroupOrderStatus.DELIVERED, null, List.of(new Participant(park.getId(), 7000L, true))));

        assertThat(scoreOf(lee)).isZero();
        assertThat(scoreOf(park)).isEqualTo(100);
    }

    @Test
    @DisplayName("배달비 수정처럼 상태가 그대로인 글은 점수와 상관없다")
    void ignoresNonStatusChanges() {
        GroupOrderStatusPayload feeChanged = new GroupOrderStatusPayload(77L, 1L, "○○마라탕", host.getId(),
                GroupOrderStatus.DELIVERED, GroupOrderStatus.DELIVERED, StatusChangedBy.HOST,
                GroupOrderChangeKind.DELIVERY_FEE_CHANGED, null, null, 3000L, null,
                List.of(new Participant(lee.getId(), 9000L, true)), AT);

        record(feeChanged);

        assertThat(scoreOf(lee)).isEqualTo(50);
    }

    @Test
    @DisplayName("점수를 세기 시작한 때(counted-since)보다 앞선 글은 세지 않는다")
    void ignoresEventsBeforeCountedSince() {
        GroupOrderStatusPayload old = new GroupOrderStatusPayload(77L, 1L, "○○마라탕", host.getId(),
                GroupOrderStatus.ORDERED, GroupOrderStatus.DELIVERED, StatusChangedBy.HOST, null, null, 3000L,
                List.of(new Participant(lee.getId(), 9000L, true)), Instant.parse("1999-12-31T00:00:00Z"));

        record(old);

        assertThat(scoreOf(lee)).isEqualTo(50);
    }

    @Test
    @DisplayName("변동 기록에 이유와 점수가 남는다(화면이 \"왜 바뀌었나\"를 보여 준다)")
    void keepsReason() {
        record(ended(GroupOrderStatus.CANCELED, CancelType.HOST_WHILE_SETTLING, List.of(new Participant(host.getId(), 5000L, true))));

        TrustScoreChange change = trustScoreRepository.findTop20ByUserIdOrderByIdDesc(host.getId()).getFirst();
        assertThat(change.getReason()).isEqualTo(TrustReason.HOST_CANCELED_WHILE_SETTLING);
        assertThat(change.getDelta()).isEqualTo(-15);
        assertThat(change.getStoreName()).isEqualTo("○○마라탕");
    }
}
