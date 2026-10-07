package com.bandal.user;

import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.JwtProvider;
import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.grouporder.GroupOrderStatus;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpot;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.settlement.SettlementRepository;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 송금받을 계좌의 등록과 변경 (ADR-031, 041)
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountApiTest {

    static final String ACCOUNT_BODY = """
            {"bankName":"한국은행","accountNumber":"110-999-888","accountHolder":"이영희"}
            """;

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

    University university;
    PickupSpot pickupSpot;
    // 계좌가 있는 방장
    User host;
    // 계좌가 없는 사용자
    User member;

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

        host = saveUser("kim@hankuk.ac.kr", "배고파", true, true);
        member = saveUser("lee@hankuk.ac.kr", "마라탕러버", false, true);
    }

    // 이 테스트가 보려는 건 계좌 API라 준비물은 값을 바로 넣는다
    User saveUser(String email, String nickname, boolean withAccount, boolean verified) {
        User user = new User(university, email, "hashed-password", nickname);
        if (verified) {
            ReflectionTestUtils.setField(user, "emailVerifiedAt", Instant.now());
        }
        if (withAccount) {
            ReflectionTestUtils.setField(user, "bankName", "한국은행");
            ReflectionTestUtils.setField(user, "accountNumber", "110123456789");
            ReflectionTestUtils.setField(user, "accountHolder", "김민수");
        }
        return userRepository.save(user);
    }

    String bearer(Long userId) {
        return "Bearer " + jwtProvider.createAccessToken(userId);
    }

    ResultActions registerAccount(Long userId, String body) throws Exception {
        return mockMvc.perform(put("/api/users/me/account")
                .header("Authorization", bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // 방장의 방을 원하는 상태로 만든다. 정산 API는 아직 없어서 상태를 바로 넣는다
    GroupOrder roomOf(User roomHost, GroupOrderStatus status) {
        GroupOrder groupOrder = new GroupOrder(roomHost, pickupSpot, "○○마라탕", 15_000,
                Instant.now().plus(2, ChronoUnit.HOURS), 4);
        ReflectionTestUtils.setField(groupOrder, "status", status);
        return groupOrderRepository.save(groupOrder);
    }

    User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    @Nested
    @DisplayName("등록")
    class Register {

        @Test
        @DisplayName("등록하면 204이고, 계좌번호는 숫자만 저장된다")
        void registers() throws Exception {
            registerAccount(member.getId(), ACCOUNT_BODY)
                    .andExpect(status().isNoContent());

            User saved = reload(member);
            assertThat(saved.getBankName()).isEqualTo("한국은행");
            assertThat(saved.getAccountNumber()).isEqualTo("110999888");
            assertThat(saved.getAccountHolder()).isEqualTo("이영희");
        }

        @Test
        @DisplayName("이미 계좌가 있어도 다시 보내면 새 계좌로 바뀐다")
        void overwrites() throws Exception {
            registerAccount(host.getId(), """
                    {"bankName":"우리은행","accountNumber":"1002-111-222","accountHolder":"김민수"}
                    """)
                    .andExpect(status().isNoContent());

            User saved = reload(host);
            assertThat(saved.getBankName()).isEqualTo("우리은행");
            assertThat(saved.getAccountNumber()).isEqualTo("1002111222");
        }

        @Test
        @DisplayName("은행이 비면 400이다")
        void rejectsBlankBank() throws Exception {
            registerAccount(member.getId(), """
                    {"bankName":"  ","accountNumber":"110-999-888","accountHolder":"이영희"}
                    """)
                    .andExpect(status().isBadRequest());

            assertThat(reload(member).hasAccount()).isFalse();
        }

        @Test
        @DisplayName("계좌번호가 하이픈뿐이면 400이다. 형식 검사는 통과하지만 엔티티가 거른다")
        void rejectsAccountNumberWithoutDigits() throws Exception {
            registerAccount(member.getId(), """
                    {"bankName":"한국은행","accountNumber":"---","accountHolder":"이영희"}
                    """)
                    .andExpect(status().isBadRequest());

            assertThat(reload(member).hasAccount()).isFalse();
        }

        @Test
        @DisplayName("로그인이 필요하다")
        void requiresLogin() throws Exception {
            mockMvc.perform(put("/api/users/me/account")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ACCOUNT_BODY))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("메일 인증을 안 했으면 403이다. 쓰기는 인증한 사람만 한다 (ADR-027)")
        void requiresVerifiedEmail() throws Exception {
            User unverified = saveUser("choi@hankuk.ac.kr", "떡볶이", false, false);

            registerAccount(unverified.getId(), ACCOUNT_BODY)
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("정산 중에는 바꿀 수 없다 (ADR-041)")
    class LockedWhileSettling {

        @Test
        @DisplayName("방장인 방이 정산중이면 409이고 계좌는 그대로다")
        void rejectsWhileHostingSettlingRoom() throws Exception {
            roomOf(host, GroupOrderStatus.SETTLING);

            registerAccount(host.getId(), ACCOUNT_BODY)
                    .andExpect(status().isConflict());

            assertThat(reload(host).getAccountNumber()).isEqualTo("110123456789");
        }

        @Test
        @DisplayName("방장인 방이 여러 개여도 하나라도 정산중이면 409다")
        void rejectsWhenAnyRoomIsSettling() throws Exception {
            roomOf(host, GroupOrderStatus.RECRUITING);
            roomOf(host, GroupOrderStatus.SETTLING);

            registerAccount(host.getId(), ACCOUNT_BODY)
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("마감 상태에서는 아무도 계좌를 본 적이 없어서 바꿀 수 있다")
        void allowsWhileClosed() throws Exception {
            roomOf(host, GroupOrderStatus.CLOSED);

            registerAccount(host.getId(), ACCOUNT_BODY)
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("주문완료 뒤에는 송금이 끝나서 바꿀 수 있다")
        void allowsAfterOrdered() throws Exception {
            roomOf(host, GroupOrderStatus.ORDERED);

            registerAccount(host.getId(), ACCOUNT_BODY)
                    .andExpect(status().isNoContent());

            assertThat(reload(host).getAccountNumber()).isEqualTo("110999888");
        }

        @Test
        @DisplayName("남의 정산중인 방은 상관없다. 막히는 건 그 방의 방장뿐이다")
        void allowsWhenSomeoneElsesRoomIsSettling() throws Exception {
            roomOf(host, GroupOrderStatus.SETTLING);

            registerAccount(member.getId(), ACCOUNT_BODY)
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("방 만들기와의 관계 (ADR-031)")
    class CreatingRoom {

        String roomBody() {
            return """
                    {"pickupSpotId": %d, "storeName": "△△치킨", "minOrderAmount": 15000,
                     "deadlineAt": "%s", "capacity": 4}
                    """.formatted(pickupSpot.getId(), Instant.now().plus(2, ChronoUnit.HOURS));
        }

        @Test
        @DisplayName("계좌가 없으면 방을 만들 수 없다")
        void rejectsHostWithoutAccount() throws Exception {
            // 돈 받을 곳이 없는 사람이 방장을 할 수는 없다
            mockMvc.perform(post("/api/group-orders")
                            .header("Authorization", bearer(member.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(roomBody()))
                    .andExpect(status().isConflict());

            assertThat(groupOrderRepository.count()).isZero();
        }

        @Test
        @DisplayName("계좌를 등록하면 방을 만들 수 있다")
        void allowsAfterRegistering() throws Exception {
            registerAccount(member.getId(), ACCOUNT_BODY)
                    .andExpect(status().isNoContent());

            mockMvc.perform(post("/api/group-orders")
                            .header("Authorization", bearer(member.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(roomBody()))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("내 정보의 hasAccount로 방 만들기 전에 계좌가 있는지 알 수 있다")
        void meShowsHasAccount() throws Exception {
            mockMvc.perform(get("/api/auth/me")
                            .header("Authorization", bearer(member.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hasAccount").value(false))
                    // 계좌 자체는 싣지 않는다
                    .andExpect(jsonPath("$.accountNumber").doesNotExist());

            registerAccount(member.getId(), ACCOUNT_BODY);

            mockMvc.perform(get("/api/auth/me")
                            .header("Authorization", bearer(member.getId())))
                    .andExpect(jsonPath("$.hasAccount").value(true));
        }
    }
}
