package com.bandal.user;

import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.dto.LoginRequest;
import com.bandal.auth.dto.RefreshRequest;
import com.bandal.grouporder.CancelType;
import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.grouporder.GroupOrderStatus;
import com.bandal.notification.NotificationRepository;
import com.bandal.outbox.OutboxMessage;
import com.bandal.outbox.OutboxMessageRepository;
import com.bandal.outbox.OutboxType;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.Participation;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpot;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.settlement.Settlement;
import com.bandal.settlement.SettlementFixtures;
import com.bandal.settlement.SettlementRepository;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 탈퇴: 줄은 남기고 개인정보를 지운다. 할 일이 남았으면 막는다. 탈퇴 뒤에는 남은 토큰으로 아무것도 못 한다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WithdrawApiTest {

    static final String PASSWORD = "password1234";
    static final String JISU_EMAIL = "jisu@hankuk.ac.kr";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PasswordEncoder passwordEncoder;

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
    NotificationRepository notificationRepository;

    @Autowired
    OutboxMessageRepository outboxMessageRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    University hankuk;
    PickupSpot pickupSpot;
    User jisu;
    User host;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        outboxMessageRepository.deleteAll();
        settlementRepository.deleteAll();
        orderItemRepository.deleteAll();
        participationRepository.deleteAll();
        groupOrderRepository.deleteAll();
        userRepository.deleteAll();
        pickupSpotRepository.deleteAll();
        universityRepository.deleteAll();

        hankuk = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        pickupSpot = pickupSpotRepository.save(new PickupSpot(hankuk, "제1기숙사 로비", null));
        jisu = saveUser(JISU_EMAIL, "마라탕러버");
        host = saveUser("host@hankuk.ac.kr", "방장님");
    }

    // 인증을 마치고 계좌도 있는 사용자(방장이 될 수 있다)
    User saveUser(String email, String nickname) {
        User user = new User(hankuk, email, passwordEncoder.encode(PASSWORD), nickname);
        ReflectionTestUtils.setField(user, "emailVerifiedAt", Instant.now());
        ReflectionTestUtils.setField(user, "bankName", "한국은행");
        ReflectionTestUtils.setField(user, "accountNumber", "110123456789");
        ReflectionTestUtils.setField(user, "accountHolder", "김지수");
        return userRepository.save(user);
    }

    // 원하는 상태의 방을 바로 만든다
    GroupOrder roomOf(User roomHost, GroupOrderStatus status) {
        GroupOrder room = new GroupOrder(roomHost, pickupSpot, "○○마라탕", 15000,
                Instant.now().plus(2, ChronoUnit.HOURS), 4);
        ReflectionTestUtils.setField(room, "status", status);
        return groupOrderRepository.save(room);
    }

    // 정산중에 취소된 방과 참여자 줄 하나. 참여자는 "보냈어요"를 누른 상태(환불 대상)
    Settlement refundLine(User roomHost, User member) {
        GroupOrder room = roomOf(roomHost, GroupOrderStatus.CANCELED);
        ReflectionTestUtils.setField(room, "cancelType", CancelType.HOST_WHILE_SETTLING);
        groupOrderRepository.save(room);
        participationRepository.save(new Participation(room, member, Instant.now()));
        Settlement line = SettlementFixtures.line(room, member, false);
        ReflectionTestUtils.setField(line, "markedPaidAt", Instant.now());
        return settlementRepository.save(line);
    }

    String login(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    String field(String json, String name) {
        return objectMapper.readTree(json).get(name).asString();
    }

    ResultActions withdraw(String accessToken, String password) throws Exception {
        return mockMvc.perform(delete("/api/users/me")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"password": "%s"}
                        """.formatted(password)));
    }

    User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    @Test
    @DisplayName("탈퇴하면 줄은 남고 메일, 닉네임, 계좌, 실명이 지워진다")
    void anonymizes() throws Exception {
        withdraw(field(login(JISU_EMAIL), "accessToken"), PASSWORD).andExpect(status().isNoContent());

        User gone = reload(jisu);
        assertThat(gone.isDeleted()).isTrue();
        assertThat(gone.getEmail()).isNull();
        assertThat(gone.getNickname()).isNull();
        assertThat(gone.getBankName()).isNull();
        assertThat(gone.getAccountNumber()).isNull();
        assertThat(gone.getAccountHolder()).isNull();
        assertThat(gone.displayName()).isEqualTo("탈퇴한 사용자");
        // 비밀번호 칸은 비울 수 없어 다른 값으로 덮인다. 원래 비밀번호로는 맞지 않는다
        assertThat(passwordEncoder.matches(PASSWORD, gone.getPassword())).isFalse();
    }

    @Test
    @DisplayName("탈퇴 뒤 남은 액세스 토큰으로는 401이다(만료 전이어도)")
    void accessTokenStopsWorking() throws Exception {
        String accessToken = field(login(JISU_EMAIL), "accessToken");

        withdraw(accessToken, PASSWORD).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/group-orders")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pickupSpotId": %d, "storeName": "○○마라탕", "minOrderAmount": 15000,
                                 "deadlineAt": "%s", "capacity": 4}
                                """.formatted(pickupSpot.getId(), Instant.now().plus(2, ChronoUnit.HOURS))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("탈퇴하면 모든 기기의 리프레시 토큰으로 더 갱신할 수 없다")
    void refreshTokensRevoked() throws Exception {
        String pc = login(JISU_EMAIL);
        String phone = login(JISU_EMAIL);

        withdraw(field(phone, "accessToken"), PASSWORD).andExpect(status().isNoContent());

        for (String device : new String[]{pc, phone}) {
            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new RefreshRequest(field(device, "refreshToken")))))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("탈퇴한 메일과 비밀번호로는 로그인할 수 없다")
    void cannotLogIn() throws Exception {
        withdraw(field(login(JISU_EMAIL), "accessToken"), PASSWORD).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(JISU_EMAIL, PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("같은 학교 메일과 같은 닉네임으로 다시 가입할 수 있고, 새 계정이 생긴다")
    void canSignUpAgain() throws Exception {
        withdraw(field(login(JISU_EMAIL), "accessToken"), PASSWORD).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s", "nickname": "마라탕러버", "universityId": %d}
                                """.formatted(JISU_EMAIL, PASSWORD, hankuk.getId())))
                .andExpect(status().isAccepted());

        User again = userRepository.findByEmail(JISU_EMAIL).orElseThrow();
        assertThat(again.getId()).isNotEqualTo(jisu.getId());
    }

    @Test
    @DisplayName("탈퇴자가 여럿이어도 된다(비운 메일, 닉네임이 서로 부딪히지 않는다)")
    void manyWithdrawals() throws Exception {
        withdraw(field(login(JISU_EMAIL), "accessToken"), PASSWORD).andExpect(status().isNoContent());

        withdraw(field(login("host@hankuk.ac.kr"), "accessToken"), PASSWORD).andExpect(status().isNoContent());

        assertThat(reload(host).isDeleted()).isTrue();
    }

    @Test
    @DisplayName("비밀번호가 틀리면 400이고 아무것도 지워지지 않는다")
    void wrongPassword() throws Exception {
        withdraw(field(login(JISU_EMAIL), "accessToken"), "wrong-password")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("비밀번호가 맞지 않습니다"));

        assertThat(reload(jisu).isDeleted()).isFalse();
        assertThat(reload(jisu).getEmail()).isEqualTo(JISU_EMAIL);
    }

    @Test
    @DisplayName("끝나지 않은 방의 방장이면 409다")
    void blockedWhileHosting() throws Exception {
        roomOf(jisu, GroupOrderStatus.RECRUITING);

        withdraw(field(login(JISU_EMAIL), "accessToken"), PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("진행 중인 방이나 끝나지 않은 환불이 있어 탈퇴할 수 없습니다. 끝난 뒤에 다시 시도해주세요"));
        assertThat(reload(jisu).isDeleted()).isFalse();
    }

    @Test
    @DisplayName("끝나지 않은 방에 참여 중이면 409다")
    void blockedWhileJoined() throws Exception {
        GroupOrder room = roomOf(host, GroupOrderStatus.SETTLING);
        participationRepository.save(new Participation(room, jisu, Instant.now()));

        withdraw(field(login(JISU_EMAIL), "accessToken"), PASSWORD).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("정산중에 취소된 방에서 돌려받을 환불이 남았으면 409다(방 상태는 취소여도)")
    void blockedWhileRefundOwedToMe() throws Exception {
        refundLine(host, jisu);

        withdraw(field(login(JISU_EMAIL), "accessToken"), PASSWORD).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("방장으로서 돌려줄 환불이 남았으면 409다. 방장이 \"돌려줬어요\"만 누르고 참여자 확인 전이어도 남은 것이다")
    void blockedWhileRefundOwedByMe() throws Exception {
        Settlement line = refundLine(jisu, host);
        ReflectionTestUtils.setField(line, "refundSentAt", Instant.now());
        settlementRepository.save(line);

        withdraw(field(login(JISU_EMAIL), "accessToken"), PASSWORD).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("환불이 끝났거나(돌려받았어요) 입금이 안 됐다고 정리된 줄만 있으면 탈퇴할 수 있다")
    void settledRefundsDoNotBlock() throws Exception {
        Settlement received = refundLine(host, jisu);
        ReflectionTestUtils.setField(received, "refundReceivedAt", Instant.now());
        settlementRepository.save(received);
        Settlement denied = refundLine(host, jisu);
        ReflectionTestUtils.setField(denied, "paymentDeniedAt", Instant.now());
        settlementRepository.save(denied);

        withdraw(field(login(JISU_EMAIL), "accessToken"), PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("끝난 방의 방장이 탈퇴하면 그 방은 남고 방장 이름이 \"탈퇴한 사용자\"로 보인다")
    void finishedRoomShowsWithdrawnHost() throws Exception {
        GroupOrder room = roomOf(jisu, GroupOrderStatus.DELIVERED);
        participationRepository.save(new Participation(room, host, Instant.now()));

        withdraw(field(login(JISU_EMAIL), "accessToken"), PASSWORD).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/group-orders/" + room.getId())
                        .header("Authorization", "Bearer " + field(login("host@hankuk.ac.kr"), "accessToken")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostNickname").value("탈퇴한 사용자"));
    }

    @Test
    @DisplayName("내 알림과 아직 안 보낸 내 메일은 지워지고, 다른 사람 것은 그대로다")
    void removesNotificationsAndPendingMail() throws Exception {
        GroupOrder room = roomOf(host, GroupOrderStatus.DELIVERED);
        for (User user : new User[]{jisu, host}) {
            // 줄을 바꾸는 쿼리(@Modifying)는 트랜잭션 안에서만 돈다. 서비스 밖에서 부르니 직접 연다
            transactionTemplate.executeWithoutResult(status -> notificationRepository.insertIfAbsent(
                    1L, user.getId(), room.getId(), "○○마라탕", "STATUS_CHANGED",
                    "DELIVERED", null, null, null, null, Instant.now()));
            // 집배원이 먼저 보내 버리지 않게 보낼 때를 한 시간 뒤로 둔다
            outboxMessageRepository.save(OutboxMessage.mail(OutboxType.ALREADY_REGISTERED_MAIL, user.getId(), Instant.now().plus(1, ChronoUnit.HOURS)));
        }

        withdraw(field(login(JISU_EMAIL), "accessToken"), PASSWORD).andExpect(status().isNoContent());

        assertThat(notificationRepository.findAll()).extracting("userId").containsExactly(host.getId());
        assertThat(outboxMessageRepository.findAll()).extracting("userId").containsExactly(host.getId());
    }
}
