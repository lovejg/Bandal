package com.bandal.grouporder;

import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.JwtProvider;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.Participation;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpot;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 스케줄러가 멈춘 방을 찾아 넘기는 한 바퀴
// 실제로 1분을 기다리지 않는다. 시각을 앞으로 당겨 runAt에 넘긴다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GroupOrderExpiryTest {

    static final long HOST_MENU = 8_000;
    static final long MEMBER_MENU = 9_000;

    @Autowired
    GroupOrderExpiryScheduler scheduler;

    // 평소에는 진짜 서비스 그대로 돈다. 실패 격리 테스트에서만 특정 방을 터뜨린다
    @MockitoSpyBean
    GroupOrderService groupOrderService;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtProvider jwtProvider;

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

    PickupSpot pickupSpot;
    User host;
    User member;
    // 방을 만든 시각. 여기서 앞으로 당긴 시각으로 스케줄러를 부른다
    Instant now;

    @BeforeEach
    void setUp() {
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

    // 2시간 뒤 마감인 모집중 방. withMember면 참여자까지 담아 최소주문금액을 넘긴다
    GroupOrder recruitingRoom(boolean withMember) {
        GroupOrder room = groupOrderRepository.save(new GroupOrder(
                host, pickupSpot, "○○마라탕", 15_000, now.plus(2, ChronoUnit.HOURS), 4));
        Participation hostPart = participationRepository.save(new Participation(room, host, now));
        orderItemRepository.save(hostPart.addItem(host.getId(), "마라탕", null, HOST_MENU, 1, now));
        if (withMember) {
            Participation memberPart = participationRepository.save(new Participation(room, member, now));
            orderItemRepository.save(memberPart.addItem(member.getId(), "마라탕 소", null, MEMBER_MENU, 1, now));
        }
        return room;
    }

    // 지금 마감된 방
    GroupOrder closedRoom() {
        GroupOrder room = recruitingRoom(true);
        room.closeByHost(host.getId(), 2, HOST_MENU + MEMBER_MENU, now);
        return groupOrderRepository.saveAndFlush(room);
    }

    // 지금 정산을 시작한 방. 정산표 줄은 없다. 상태만 본다
    GroupOrder settlingRoom() {
        GroupOrder room = closedRoom();
        room.startSettlement(host.getId(), 3_500, now);
        return groupOrderRepository.saveAndFlush(room);
    }

    // 지금 주문완료된 방
    GroupOrder orderedRoom() {
        GroupOrder room = settlingRoom();
        room.order(host.getId(), null, now.plusSeconds(2400), now);
        return groupOrderRepository.saveAndFlush(room);
    }

    GroupOrder reload(GroupOrder room) {
        return groupOrderRepository.findById(room.getId()).orElseThrow();
    }

    @Test
    @DisplayName("마감 시각이 지난 모집중 방은 조건을 채웠으면 마감된다")
    void closesRecruitingPastDeadline() {
        GroupOrder room = recruitingRoom(true);
        Instant at = room.getDeadlineAt().plusSeconds(60);

        scheduler.runAt(at);

        assertThat(reload(room).getStatus()).isEqualTo(GroupOrderStatus.CLOSED);
        assertThat(reload(room).getClosedAt()).isEqualTo(at);
    }

    @Test
    @DisplayName("마감 시각이 지난 모집중 방은 조건 미달이면 취소된다")
    void cancelsRecruitingUnmet() {
        // 방장 혼자라 인원 미달
        GroupOrder room = recruitingRoom(false);

        scheduler.runAt(room.getDeadlineAt().plusSeconds(60));

        assertThat(reload(room).getStatus()).isEqualTo(GroupOrderStatus.CANCELED);
        assertThat(reload(room).getCancelType()).isEqualTo(CancelType.DEADLINE_UNMET);
    }

    @Test
    @DisplayName("마감 시각 전인 모집중 방은 그대로다")
    void keepsRecruitingBeforeDeadline() {
        GroupOrder room = recruitingRoom(true);

        scheduler.runAt(room.getDeadlineAt().minusSeconds(60));

        assertThat(reload(room).getStatus()).isEqualTo(GroupOrderStatus.RECRUITING);
    }

    @Test
    @DisplayName("마감된 지 3시간이 지난 방은 자동 취소된다")
    void cancelsStaleClosed() {
        GroupOrder room = closedRoom();

        scheduler.runAt(now.plus(GroupOrder.CLOSED_EXPIRY).plusSeconds(60));

        assertThat(reload(room).getStatus()).isEqualTo(GroupOrderStatus.CANCELED);
        assertThat(reload(room).getCancelType()).isEqualTo(CancelType.CLOSED_TIMEOUT);
    }

    @Test
    @DisplayName("마감된 지 3시간이 안 된 방은 그대로다")
    void keepsFreshClosed() {
        GroupOrder room = closedRoom();

        scheduler.runAt(now.plus(GroupOrder.CLOSED_EXPIRY).minusSeconds(60));

        assertThat(reload(room).getStatus()).isEqualTo(GroupOrderStatus.CLOSED);
    }

    @Test
    @DisplayName("정산중이 된 지 24시간이 지난 방은 자동 취소된다")
    void cancelsStaleSettling() {
        GroupOrder room = settlingRoom();

        scheduler.runAt(now.plus(GroupOrder.SETTLING_EXPIRY).plusSeconds(60));

        assertThat(reload(room).getStatus()).isEqualTo(GroupOrderStatus.CANCELED);
        assertThat(reload(room).getCancelType()).isEqualTo(CancelType.SETTLING_TIMEOUT);
    }

    @Test
    @DisplayName("주문완료된 지 6시간이 지난 방은 자동 배달완료된다")
    void deliversStaleOrdered() {
        GroupOrder room = orderedRoom();

        scheduler.runAt(now.plus(GroupOrder.ORDERED_EXPIRY).plusSeconds(60));

        assertThat(reload(room).getStatus()).isEqualTo(GroupOrderStatus.DELIVERED);
        assertThat(reload(room).getDeliveredAt()).isNotNull();
    }

    @Test
    @DisplayName("한 번 돌 때 상태가 다른 여러 방을 각자 기준으로 넘긴다")
    void handlesManyRooms() {
        GroupOrder closed = closedRoom();
        GroupOrder ordered = orderedRoom();
        GroupOrder settling = settlingRoom();

        // 6시간 뒤: 마감(3시간)과 주문완료(6시간)는 넘어가고, 정산중(24시간)은 아직이다
        scheduler.runAt(now.plus(Duration.ofHours(6)).plusSeconds(60));

        assertThat(reload(closed).getStatus()).isEqualTo(GroupOrderStatus.CANCELED);
        assertThat(reload(ordered).getStatus()).isEqualTo(GroupOrderStatus.DELIVERED);
        assertThat(reload(settling).getStatus()).isEqualTo(GroupOrderStatus.SETTLING);
    }

    @Test
    @DisplayName("방 하나를 넘기다 실패해도 나머지 방은 넘어간다")
    void isolatesFailure() {
        // 방들은 서로 독립적이다. 한 방의 데이터가 이상하거나 DB가 잠깐 끊겨도 다른 방은 멈추면 안 된다
        GroupOrder first = closedRoom();
        GroupOrder broken = closedRoom();
        GroupOrder last = closedRoom();
        doThrow(new IllegalStateException("이 방만 실패한다"))
                .when(groupOrderService).expire(eq(broken.getId()), any());

        assertThatCode(() -> scheduler.runAt(now.plus(GroupOrder.CLOSED_EXPIRY).plusSeconds(60)))
                .doesNotThrowAnyException();

        assertThat(reload(first).getStatus()).isEqualTo(GroupOrderStatus.CANCELED);
        assertThat(reload(broken).getStatus()).isEqualTo(GroupOrderStatus.CLOSED);
        assertThat(reload(last).getStatus()).isEqualTo(GroupOrderStatus.CANCELED);
    }

    @Test
    @DisplayName("자동 취소된 정산중 방에서 보낸 사람은 환불 대기를 보고 돌려받았어요를 누를 수 있다")
    void opensRefundAfterSettlingTimeout() throws Exception {
        // 정산 시작과 입금 확인은 실제 API로 한다. 정산표 줄이 있어야 환불을 볼 수 있다
        GroupOrder room = closedRoom();
        mockMvc.perform(post("/api/group-orders/" + room.getId() + "/delivery-fee")
                        .header("Authorization", bearer(host))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deliveryFee\": 3500}"))
                .andExpect(status().isOk());
        Long lineId = settlementRepository.findByGroupOrderIdAndUserId(room.getId(), member.getId())
                .orElseThrow().getId();
        mockMvc.perform(post("/api/settlements/" + lineId + "/confirm")
                        .header("Authorization", bearer(host)))
                .andExpect(status().isOk());

        // API가 정산 시작 시각을 실제 현재로 남겼으므로 그보다 하루 넘게 뒤로 당긴다
        scheduler.runAt(Instant.now().plus(GroupOrder.SETTLING_EXPIRY).plusSeconds(60));

        mockMvc.perform(get("/api/group-orders/" + room.getId() + "/settlement")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.lines[0].refundStatus").value("REFUND_PENDING"));
        mockMvc.perform(post("/api/settlements/" + lineId + "/receive-refund")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].refundStatus").value("REFUNDED"));
    }

    String bearer(User user) {
        return "Bearer " + jwtProvider.createAccessToken(user.getId());
    }
}
