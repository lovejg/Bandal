package com.bandal.grouporder;

import com.bandal.TestcontainersConfiguration;
import com.bandal.grouporder.dto.CancelRequest;
import com.bandal.outbox.OutboxMessageRepository;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.Participation;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpot;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.settlement.Settlement;
import com.bandal.settlement.SettlementRepository;
import com.bandal.settlement.SettlementService;
import com.bandal.settlement.dto.DeliveryFeeRequest;
import com.bandal.settlement.dto.OrderRequest;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 엔티티에 남긴 상태 기록이 정말 사건으로 방송되는가
// @RecordApplicationEvents: 이 테스트 동안 Spring이 방송한 사건을 전부 받아 둔다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class GroupOrderStatusEventPublishTest {

    static final long HOST_MENU = 8_000;
    static final long MEMBER_MENU = 9_000;

    @Autowired
    ApplicationEvents events;

    @Autowired
    GroupOrderService groupOrderService;

    @Autowired
    SettlementService settlementService;

    @Autowired
    OutboxMessageRepository outboxMessageRepository;

    @Autowired
    UniversityRepository universityRepository;

    @Autowired
    PickupSpotRepository pickupSpotRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    GroupOrderRepository groupOrderRepository;

    @Autowired
    ParticipationRepository participationRepository;

    @Autowired
    OrderItemRepository orderItemRepository;

    @Autowired
    SettlementRepository settlementRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    PickupSpot pickupSpot;
    User host;
    User member;
    Instant now;

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

        University university = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        pickupSpot = pickupSpotRepository.save(new PickupSpot(university, "제1기숙사 로비", null));
        host = saveUser(university, "kim@hankuk.ac.kr", "배고파");
        member = saveUser(university, "lee@hankuk.ac.kr", "마라탕러버");
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    User saveUser(University university, String email, String nickname) {
        User user = new User(university, email, "hashed-password", nickname);
        ReflectionTestUtils.setField(user, "emailVerifiedAt", Instant.now());
        ReflectionTestUtils.setField(user, "bankName", "한국은행");
        ReflectionTestUtils.setField(user, "accountNumber", "110-123-456789");
        ReflectionTestUtils.setField(user, "accountHolder", "김민수");
        return userRepository.save(user);
    }

    // 방장과 참여자 한 명이 메뉴를 담아 최소주문금액을 넘긴 모집중 방
    GroupOrder recruitingRoom() {
        GroupOrder room = groupOrderRepository.save(new GroupOrder(
                host, pickupSpot, "○○마라탕", 15_000, now.plus(2, ChronoUnit.HOURS), 4));
        Participation hostPart = participationRepository.save(new Participation(room, host, now));
        orderItemRepository.save(hostPart.addItem(host.getId(), "마라탕", null, HOST_MENU, 1, now));
        Participation memberPart = participationRepository.save(new Participation(room, member, now));
        orderItemRepository.save(memberPart.addItem(member.getId(), "마라탕 소", null, MEMBER_MENU, 1, now));
        return room;
    }

    List<GroupOrderStatusChanged> published() {
        return events.stream(GroupOrderStatusChanged.class).toList();
    }

    @Test
    @DisplayName("대조군: 상태를 바꾸고 리포지토리 save를 부르면 사건이 방송된다")
    void publishedOnSave() {
        GroupOrder room = recruitingRoom();

        room.closeByHost(host.getId(), 2, HOST_MENU + MEMBER_MENU, now);
        groupOrderRepository.save(room);

        assertThat(published()).containsExactly(new GroupOrderStatusChanged(
                room.getId(), GroupOrderStatus.RECRUITING, GroupOrderStatus.CLOSED, StatusChangedBy.HOST, now));
    }

    @Test
    @DisplayName("대조군: save 없이 변경 감지에만 맡기면 상태는 커밋되지만 사건은 하나도 나가지 않는다")
    void notPublishedWithoutSave() {
        GroupOrder room = recruitingRoom();

        // 서비스 메서드처럼 트랜잭션 하나 안에서 방을 꺼내 상태만 바꾸고 save는 부르지 않는다
        transactionTemplate.executeWithoutResult(status -> {
            GroupOrder managed = groupOrderRepository.findById(room.getId()).orElseThrow();
            managed.closeByHost(host.getId(), 2, HOST_MENU + MEMBER_MENU, now);
        });

        // 커밋할 때 Hibernate가 바뀐 칸을 알아채 UPDATE를 낸다(변경 감지). 그래서 상태는 마감으로 바뀌어 있다
        assertThat(groupOrderRepository.findById(room.getId()).orElseThrow().getStatus()).isEqualTo(GroupOrderStatus.CLOSED);
        // 하지만 엔티티에 쌓인 상태 기록(@DomainEvents)은 리포지토리 save를 지날 때만 꺼내 방송된다. 에러도 경고도 없이 사라진다
        assertThat(published()).isEmpty();
        assertThat(outboxMessageRepository.findAll()).isEmpty();
    }

    // 사건이 정확히 하나, 이 방의 from → to로 방송됐는가. 실제 상태도 to로 커밋됐는가
    void assertPublishedOnce(GroupOrder room, GroupOrderStatus from, GroupOrderStatus to) {
        List<GroupOrderStatusChanged> published = published();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).groupOrderId()).isEqualTo(room.getId());
        assertThat(published.get(0).from()).isEqualTo(from);
        assertThat(published.get(0).to()).isEqualTo(to);
        // 상태는 커밋됐는데 사건만 없는 경우를 가린다
        assertThat(groupOrderRepository.findById(room.getId()).orElseThrow().getStatus()).isEqualTo(to);
    }

    // 마감된 방. 만드는 동안 나간 사건은 지우고 시작한다
    GroupOrder closedRoom() {
        GroupOrder room = recruitingRoom();
        room.closeByHost(host.getId(), 2, HOST_MENU + MEMBER_MENU, now);
        room = groupOrderRepository.saveAndFlush(room);
        events.clear();
        return room;
    }

    // 정산중이고 참여자 입금까지 확인된 방
    GroupOrder settledRoom() {
        GroupOrder room = closedRoom();
        settlementService.start(room.getId(), host.getId(), new DeliveryFeeRequest(3_000L));
        Settlement memberLine = settlementRepository.findByGroupOrderId(room.getId()).stream()
                .filter(line -> !line.isHost())
                .findFirst().orElseThrow();
        settlementService.markPaid(memberLine.getId(), member.getId());
        settlementService.confirm(memberLine.getId(), host.getId());
        events.clear();
        return room;
    }

    @Nested
    @DisplayName("상태를 바꾸는 서비스 경로마다 사건이 하나 방송된다")
    class ThroughService {

        @Test
        @DisplayName("방장 마감")
        void close() {
            GroupOrder room = recruitingRoom();

            groupOrderService.close(room.getId(), host.getId());

            assertPublishedOnce(room, GroupOrderStatus.RECRUITING, GroupOrderStatus.CLOSED);
        }

        @Test
        @DisplayName("방장 취소")
        void cancel() {
            GroupOrder room = recruitingRoom();

            groupOrderService.cancel(room.getId(), host.getId(), new CancelRequest(null));

            assertPublishedOnce(room, GroupOrderStatus.RECRUITING, GroupOrderStatus.CANCELED);
        }

        @Test
        @DisplayName("스케줄러: 마감 시각이 지난 모집중 방을 마감")
        void expireRecruiting() {
            GroupOrder room = recruitingRoom();

            groupOrderService.expire(room.getId(), now.plus(3, ChronoUnit.HOURS));

            assertPublishedOnce(room, GroupOrderStatus.RECRUITING, GroupOrderStatus.CLOSED);
        }

        @Test
        @DisplayName("스케줄러: 마감에서 오래 멈춘 방을 취소")
        void expireStale() {
            GroupOrder room = closedRoom();

            groupOrderService.expire(room.getId(), now.plus(GroupOrder.CLOSED_EXPIRY));

            assertPublishedOnce(room, GroupOrderStatus.CLOSED, GroupOrderStatus.CANCELED);
        }

        @Test
        @DisplayName("정산 시작")
        void startSettlement() {
            GroupOrder room = closedRoom();

            settlementService.start(room.getId(), host.getId(), new DeliveryFeeRequest(3_000L));

            assertPublishedOnce(room, GroupOrderStatus.CLOSED, GroupOrderStatus.SETTLING);
        }

        @Test
        @DisplayName("주문완료")
        void order() {
            GroupOrder room = settledRoom();

            settlementService.order(room.getId(), host.getId(), new OrderRequest(null));

            assertPublishedOnce(room, GroupOrderStatus.SETTLING, GroupOrderStatus.ORDERED);
        }

        @Test
        @DisplayName("배달완료")
        void deliver() {
            GroupOrder room = settledRoom();
            settlementService.order(room.getId(), host.getId(), new OrderRequest(null));
            events.clear();

            settlementService.deliver(room.getId(), host.getId());

            assertPublishedOnce(room, GroupOrderStatus.ORDERED, GroupOrderStatus.DELIVERED);
        }

        @Test
        @DisplayName("배달비 수정: 상태는 그대로(정산중 → 정산중)인 변경 기록이 하나 나간다")
        void changeDeliveryFee() {
            // 아직 아무도 "보냈어요"를 누르지 않은 정산중 방. 입금 기록이 있으면 배달비를 고칠 수 없다
            GroupOrder room = closedRoom();
            settlementService.start(room.getId(), host.getId(), new DeliveryFeeRequest(3_000L));
            events.clear();

            settlementService.changeDeliveryFee(room.getId(), host.getId(), new DeliveryFeeRequest(4_000L));

            assertPublishedOnce(room, GroupOrderStatus.SETTLING, GroupOrderStatus.SETTLING);
            assertThat(published().get(0).kind()).isEqualTo(GroupOrderChangeKind.DELIVERY_FEE_CHANGED);
            assertThat(published().get(0).changedBy()).isEqualTo(StatusChangedBy.HOST);
        }
    }
}
