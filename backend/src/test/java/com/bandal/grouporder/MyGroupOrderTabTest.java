package com.bandal.grouporder;

import com.bandal.TestcontainersConfiguration;
import com.bandal.grouporder.dto.CancelRequest;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.Participation;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpot;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.settlement.RefundStatus;
import com.bandal.settlement.Settlement;
import com.bandal.settlement.SettlementRepository;
import com.bandal.settlement.SettlementService;
import com.bandal.settlement.dto.DeliveryFeeRequest;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 내 방 목록의 탭 판정이 엔티티의 환불 규칙과 맞는지
// "환불 대상이고 아직 안 받음"이 엔티티(refundStatus)와 쿼리(EXISTS) 두 곳에 있다.
// 한쪽만 바뀌면 여기가 빨간불이 된다. 기대값을 손으로 적지 않고 엔티티에게 물어서 만든다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MyGroupOrderTabTest {

    @Autowired
    GroupOrderRepository groupOrderRepository;

    @Autowired
    GroupOrderService groupOrderService;

    @Autowired
    SettlementService settlementService;

    @Autowired
    SettlementRepository settlementRepository;

    @Autowired
    ParticipationRepository participationRepository;

    @Autowired
    OrderItemRepository orderItemRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PickupSpotRepository pickupSpotRepository;

    @Autowired
    UniversityRepository universityRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    University university;
    PickupSpot pickupSpot;
    User host;
    User memberA;
    User memberC;

    @BeforeEach
    void setUp() {
        settlementRepository.deleteAll();
        orderItemRepository.deleteAll();
        participationRepository.deleteAll();
        groupOrderRepository.deleteAll();
        userRepository.deleteAll();
        pickupSpotRepository.deleteAll();
        universityRepository.deleteAll();

        university = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        pickupSpot = pickupSpotRepository.save(new PickupSpot(university, "제1기숙사 로비", null));
        host = saveUser("host@hankuk.ac.kr", "배고파");
        memberA = saveUser("a@hankuk.ac.kr", "참여자A");
        memberC = saveUser("c@hankuk.ac.kr", "참여자C");
    }

    User saveUser(String email, String nickname) {
        User user = new User(university, email, "hashed-password", nickname);
        ReflectionTestUtils.setField(user, "emailVerifiedAt", Instant.now());
        ReflectionTestUtils.setField(user, "bankName", "한국은행");
        ReflectionTestUtils.setField(user, "accountNumber", "110-123-456789");
        ReflectionTestUtils.setField(user, "accountHolder", "김민수");
        return userRepository.save(user);
    }

    // 방장과 A, C가 메뉴를 담고 마감한 뒤 배달비를 넣어 정산중이 된 방
    GroupOrder settlingRoom() {
        Instant now = Instant.now();
        GroupOrder room = groupOrderRepository.save(new GroupOrder(
                host, pickupSpot, "○○마라탕", 15_000, now.plus(2, ChronoUnit.HOURS), 4));
        for (User u : List.of(host, memberA, memberC)) {
            Participation p = participationRepository.save(new Participation(room, u, now));
            orderItemRepository.save(p.addItem(u.getId(), "마라탕", null, 8_000, 1, now));
        }
        room.closeByHost(host.getId(), 3, 24_000, now);
        groupOrderRepository.save(room);
        settlementService.start(room.getId(), host.getId(), new DeliveryFeeRequest(3_000L));
        return room;
    }

    Long lineId(GroupOrder room, User user) {
        return settlementRepository.findByGroupOrderIdAndUserId(room.getId(), user.getId()).orElseThrow().getId();
    }

    void cancelWhileSettling(GroupOrder room) {
        groupOrderService.cancel(room.getId(), host.getId(), new CancelRequest("가게가 문을 닫았습니다"));
    }

    // 쿼리가 고른 탭. 서비스를 거쳐 본다
    boolean inActive(GroupOrder room, User viewer) {
        return groupOrderService.findMyActive(viewer.getId()).rooms().stream()
                .anyMatch(r -> r.id().equals(room.getId()));
    }

    boolean inDone(GroupOrder room, User viewer) {
        return groupOrderService.findMyDone(viewer.getId(), 0, 100).rooms().stream()
                .anyMatch(r -> r.id().equals(room.getId()));
    }

    // 엔티티가 말하는 환불 상태. refundStatus()가 방의 취소 종류를 읽어서 트랜잭션 안에서 부른다
    RefundStatus refundStatus(GroupOrder room, User user) {
        return transactionTemplate.execute(status ->
                settlementRepository.findByGroupOrderIdAndUserId(room.getId(), user.getId())
                        .map(Settlement::refundStatus)
                        .orElse(null));
    }

    // 엔티티 기준으로 "환불이 안 끝났다"
    boolean refundOpen(RefundStatus status) {
        return status != null && status != RefundStatus.REFUNDED;
    }

    // 끝난 상태의 방에서, 쿼리가 고른 탭이 엔티티로 계산한 탭과 같은지 본다. 두 탭에 동시에 나오거나 둘 다 빠지면 안 된다
    void assertTabsMatchEntity(GroupOrder room) {
        boolean memberAOpen = refundOpen(refundStatus(room, memberA));
        boolean memberCOpen = refundOpen(refundStatus(room, memberC));

        assertThat(inActive(room, memberA)).as("A 진행 중").isEqualTo(memberAOpen);
        assertThat(inDone(room, memberA)).as("A 끝난 방").isEqualTo(!memberAOpen);
        assertThat(inActive(room, memberC)).as("C 진행 중").isEqualTo(memberCOpen);
        assertThat(inDone(room, memberC)).as("C 끝난 방").isEqualTo(!memberCOpen);
        // 방장은 누구 하나라도 안 끝났으면 진행 중이다
        boolean hostOpen = memberAOpen || memberCOpen;
        assertThat(inActive(room, host)).as("방장 진행 중").isEqualTo(hostOpen);
        assertThat(inDone(room, host)).as("방장 끝난 방").isEqualTo(!hostOpen);
    }

    @Test
    @DisplayName("환불 대상이 아니면(보낸 적 없음) 끝난 방이다")
    void notRefundTarget() {
        GroupOrder room = settlingRoom();
        cancelWhileSettling(room);

        assertThat(refundStatus(room, memberA)).isNull();
        assertTabsMatchEntity(room);
    }

    @Test
    @DisplayName("보냈어요만 누르고 취소되면(REFUND_PENDING) 진행 중이다")
    void refundPending() {
        GroupOrder room = settlingRoom();
        settlementService.markPaid(lineId(room, memberA), memberA.getId());
        cancelWhileSettling(room);

        assertThat(refundStatus(room, memberA)).isEqualTo(RefundStatus.REFUND_PENDING);
        assertTabsMatchEntity(room);
    }

    @Test
    @DisplayName("방장이 확인만 하고 취소되어도 환불 대상이라 진행 중이다")
    void refundPendingByConfirmOnly() {
        // 참여자가 보냈어요를 안 눌러도 방장이 받았다고 했으면 돈은 간 것이다
        GroupOrder room = settlingRoom();
        settlementService.confirm(lineId(room, memberA), host.getId());
        cancelWhileSettling(room);

        assertThat(refundStatus(room, memberA)).isEqualTo(RefundStatus.REFUND_PENDING);
        assertTabsMatchEntity(room);
    }

    @Test
    @DisplayName("입금 안 됐어요(PAYMENT_DENIED)는 다툼이 남아서 진행 중이다")
    void paymentDenied() {
        GroupOrder room = settlingRoom();
        settlementService.markPaid(lineId(room, memberA), memberA.getId());
        cancelWhileSettling(room);
        settlementService.denyPayment(lineId(room, memberA), host.getId());

        assertThat(refundStatus(room, memberA)).isEqualTo(RefundStatus.PAYMENT_DENIED);
        assertTabsMatchEntity(room);
    }

    @Test
    @DisplayName("돌려줬어요(REFUND_SENT)는 참여자 확인이 남아서 진행 중이다")
    void refundSent() {
        GroupOrder room = settlingRoom();
        settlementService.markPaid(lineId(room, memberA), memberA.getId());
        cancelWhileSettling(room);
        settlementService.sendRefund(lineId(room, memberA), host.getId());

        assertThat(refundStatus(room, memberA)).isEqualTo(RefundStatus.REFUND_SENT);
        assertTabsMatchEntity(room);
    }

    @Test
    @DisplayName("돌려받았어요(REFUNDED)면 끝난 방이다")
    void refunded() {
        GroupOrder room = settlingRoom();
        settlementService.markPaid(lineId(room, memberA), memberA.getId());
        cancelWhileSettling(room);
        settlementService.receiveRefund(lineId(room, memberA), memberA.getId());

        assertThat(refundStatus(room, memberA)).isEqualTo(RefundStatus.REFUNDED);
        assertTabsMatchEntity(room);
    }

    @Test
    @DisplayName("A는 돌려받고 C는 아직이면 A에게는 끝난 방, C와 방장에게는 진행 중이다")
    void differsByViewer() {
        GroupOrder room = settlingRoom();
        settlementService.markPaid(lineId(room, memberA), memberA.getId());
        settlementService.markPaid(lineId(room, memberC), memberC.getId());
        cancelWhileSettling(room);
        settlementService.receiveRefund(lineId(room, memberA), memberA.getId());

        assertThat(inDone(room, memberA)).isTrue();
        assertThat(inActive(room, memberC)).isTrue();
        assertThat(inActive(room, host)).isTrue();
        assertTabsMatchEntity(room);
    }

    @Test
    @DisplayName("모집중에 취소된 방은 정산표가 없어서 바로 끝난 방이다")
    void canceledWhileRecruiting() {
        GroupOrder room = groupOrderRepository.save(new GroupOrder(
                host, pickupSpot, "○○마라탕", 15_000, Instant.now().plus(2, ChronoUnit.HOURS), 4));
        participationRepository.save(new Participation(room, host, Instant.now()));
        groupOrderService.cancel(room.getId(), host.getId(), new CancelRequest(null));

        assertThat(inActive(room, host)).isFalse();
        assertThat(inDone(room, host)).isTrue();
    }

    @Test
    @DisplayName("진행 중인 상태의 방은 환불 줄과 상관없이 진행 중 탭에만 나온다")
    void progressingStatusesAreActive() {
        GroupOrder room = settlingRoom();
        settlementService.markPaid(lineId(room, memberA), memberA.getId());

        for (User viewer : List.of(host, memberA, memberC)) {
            assertThat(inActive(room, viewer)).as(viewer.getNickname()).isTrue();
            assertThat(inDone(room, viewer)).as(viewer.getNickname()).isFalse();
        }
    }
}
