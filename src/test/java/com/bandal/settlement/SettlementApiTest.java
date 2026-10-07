package com.bandal.settlement;

import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.JwtProvider;
import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.Participation;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpot;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 정산 한 바퀴. 배달비 입력 -> 송금 표시 -> 방장 확인 -> 주문완료 -> 배달완료 (ADR-029~032)
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SettlementApiTest {

    // 메뉴는 방장 8,000 + 참여자 9,000 = 17,000. 배달비 3,500을 2명이 나눈다
    static final long HOST_MENU = 8_000;
    static final long MEMBER_MENU = 9_000;
    static final long DELIVERY_FEE = 3_500;

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
    User host;
    User member;
    User outsider;
    GroupOrder groupOrder;

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

        host = saveUser("kim@hankuk.ac.kr", "배고파", true);
        member = saveUser("lee@hankuk.ac.kr", "마라탕러버", false);
        outsider = saveUser("park@hankuk.ac.kr", "꿔바로우", false);
    }

    // 메일 인증을 마친 사용자. withAccount면 송금받을 계좌까지 등록된 상태다.
    // 이 테스트가 보려는 건 정산이라 등록 과정을 거치지 않고 값을 바로 넣는다
    User saveUser(String email, String nickname, boolean withAccount) {
        User user = new User(university, email, "hashed-password", nickname);
        ReflectionTestUtils.setField(user, "emailVerifiedAt", Instant.now());
        if (withAccount) {
            ReflectionTestUtils.setField(user, "bankName", "한국은행");
            ReflectionTestUtils.setField(user, "accountNumber", "110-123-456789");
            ReflectionTestUtils.setField(user, "accountHolder", "김민수");
        }
        return userRepository.save(user);
    }

    String bearer(Long userId) {
        return "Bearer " + jwtProvider.createAccessToken(userId);
    }

    // 방장과 참여자가 메뉴를 담고 마감까지 끝난 방을 만든다
    void closedRoom() {
        groupOrder = groupOrderRepository.save(new GroupOrder(
                host, pickupSpot, "○○마라탕", 15_000, Instant.now().plus(2, ChronoUnit.HOURS), 4));

        Participation hostParticipation =
                participationRepository.save(new Participation(groupOrder, host, Instant.now()));
        Participation memberParticipation =
                participationRepository.save(new Participation(groupOrder, member, Instant.now()));
        orderItemRepository.save(hostParticipation.addItem(host.getId(), "마라탕", null, HOST_MENU, 1, Instant.now()));
        orderItemRepository.save(
                memberParticipation.addItem(member.getId(), "마라탕 소", "중간맛", MEMBER_MENU, 1, Instant.now()));

        groupOrder.closeByHost(host.getId(), 2, HOST_MENU + MEMBER_MENU, Instant.now());
        groupOrderRepository.saveAndFlush(groupOrder);
    }

    String feeBody(long deliveryFee) {
        return """
                {"deliveryFee": %d}
                """.formatted(deliveryFee);
    }

    // 배달비를 입력해 정산중까지 보낸다
    void startSettlement() throws Exception {
        closedRoom();
        mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/delivery-fee")
                        .header("Authorization", bearer(host.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feeBody(DELIVERY_FEE)))
                .andExpect(status().isOk());
    }

    Settlement lineOf(User user) {
        return settlementRepository.findByGroupOrderIdAndUserId(groupOrder.getId(), user.getId())
                .orElseThrow();
    }

    // 계좌 등록 테스트는 user/AccountApiTest로 옮겼다 (ADR-041)

    @Nested
    @DisplayName("배달비 입력")
    class StartSettlement {

        @Test
        @DisplayName("배달비를 입력하면 정산중이 되고 정산표가 생긴다")
        void createsSettlements() throws Exception {
            closedRoom();

            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/delivery-fee")
                            .header("Authorization", bearer(host.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(feeBody(DELIVERY_FEE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SETTLING"))
                    .andExpect(jsonPath("$.deliveryFee").value(DELIVERY_FEE))
                    .andExpect(jsonPath("$.lines.length()").value(2));

            assertThat(settlementRepository.findByGroupOrderId(groupOrder.getId())).hasSize(2);
            // 응답이 아니라 DB의 방 상태를 본다. 트랜잭션이 빠지면 정산표만 저장되고 방은 마감으로 남는다 (JOURNAL 2026-10-07)
            GroupOrder saved = groupOrderRepository.findById(groupOrder.getId()).orElseThrow();
            assertThat(saved.getStatus().name()).isEqualTo("SETTLING");
            assertThat(saved.getDeliveryFee()).isEqualTo(DELIVERY_FEE);
        }

        @Test
        @DisplayName("나누어떨어지지 않는 배달비의 나머지는 방장이 떠안는다")
        void hostAbsorbsRemainder() throws Exception {
            closedRoom();

            // 3,500을 2명이 나누면 1,750씩 딱 떨어지므로 3,501로 확인한다
            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/delivery-fee")
                            .header("Authorization", bearer(host.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(feeBody(3_501)))
                    .andExpect(status().isOk());

            assertThat(lineOf(member).getFeeShare()).isEqualTo(1_750);
            assertThat(lineOf(host).getFeeShare()).isEqualTo(1_751);
        }

        @Test
        @DisplayName("모든 줄의 합이 메뉴 합계 더하기 배달비와 같다")
        void sumMatches() throws Exception {
            startSettlement();

            List<Settlement> lines = settlementRepository.findByGroupOrderId(groupOrder.getId());
            long total = lines.stream().mapToLong(Settlement::getTotalAmount).sum();

            assertThat(total).isEqualTo(HOST_MENU + MEMBER_MENU + DELIVERY_FEE);
        }

        @Test
        @DisplayName("참여자마다 자기 메뉴 합계가 들어간다")
        void usesOwnMenuTotal() throws Exception {
            startSettlement();

            assertThat(lineOf(host).getMenuTotalAmount()).isEqualTo(HOST_MENU);
            assertThat(lineOf(member).getMenuTotalAmount()).isEqualTo(MEMBER_MENU);
        }

        @Test
        @DisplayName("방장 줄은 처음부터 입금이 확인된 상태다")
        void hostLineStartsConfirmed() throws Exception {
            startSettlement();

            assertThat(lineOf(host).isHost()).isTrue();
            assertThat(lineOf(host).isConfirmed()).isTrue();
            assertThat(lineOf(member).isConfirmed()).isFalse();
        }

        @Test
        @DisplayName("참여자는 배달비를 입력할 수 없다")
        void rejectsNonHost() throws Exception {
            closedRoom();

            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/delivery-fee")
                            .header("Authorization", bearer(member.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(feeBody(DELIVERY_FEE)))
                    .andExpect(status().isConflict());

            assertThat(settlementRepository.count()).isZero();
        }

        @Test
        @DisplayName("배달비 0원이면 모두 자기 메뉴값만 보낸다")
        void freeDelivery() throws Exception {
            closedRoom();

            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/delivery-fee")
                            .header("Authorization", bearer(host.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(feeBody(0)))
                    .andExpect(status().isOk());

            assertThat(lineOf(member).getFeeShare()).isZero();
            assertThat(lineOf(member).getTotalAmount()).isEqualTo(MEMBER_MENU);
            assertThat(lineOf(host).getTotalAmount()).isEqualTo(HOST_MENU);
        }

        @Test
        @DisplayName("응답에는 방장 시점의 전원 줄과 금액이 들어간다")
        void respondsHostView() throws Exception {
            closedRoom();

            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/delivery-fee")
                            .header("Authorization", bearer(host.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(feeBody(DELIVERY_FEE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.groupOrderId").value(groupOrder.getId()))
                    .andExpect(jsonPath("$.menuTotalAmount").value(HOST_MENU + MEMBER_MENU))
                    .andExpect(jsonPath("$.hostAccount.bankName").value("한국은행"))
                    .andExpect(jsonPath("$.lines.length()").value(2));

            assertThat(lineOf(member).getTotalAmount()).isEqualTo(MEMBER_MENU + 1_750);
        }

        @Test
        @DisplayName("모집중인 방에는 입력할 수 없다")
        void rejectsWhileRecruiting() throws Exception {
            groupOrder = groupOrderRepository.save(new GroupOrder(
                    host, pickupSpot, "○○마라탕", 15_000, Instant.now().plus(2, ChronoUnit.HOURS), 4));

            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/delivery-fee")
                            .header("Authorization", bearer(host.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(feeBody(DELIVERY_FEE)))
                    .andExpect(status().isConflict());

            assertThat(settlementRepository.count()).isZero();
        }

        @Test
        @DisplayName("없는 방이면 404다")
        void rejectsUnknownRoom() throws Exception {
            mockMvc.perform(post("/api/group-orders/999999/delivery-fee")
                            .header("Authorization", bearer(host.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(feeBody(DELIVERY_FEE)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("음수 배달비는 400이다")
        void rejectsNegativeFee() throws Exception {
            closedRoom();

            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/delivery-fee")
                            .header("Authorization", bearer(host.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(feeBody(-1)))
                    .andExpect(status().isBadRequest());

            assertThat(settlementRepository.count()).isZero();
        }

        @Test
        @DisplayName("로그인하지 않으면 401이다")
        void requiresLogin() throws Exception {
            closedRoom();

            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/delivery-fee")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(feeBody(DELIVERY_FEE)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("배달비가 빠지면 어느 필드인지 알려준다")
        void rejectsMissingFee() throws Exception {
            closedRoom();

            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/delivery-fee")
                            .header("Authorization", bearer(host.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("deliveryFee")));
        }

        // 배달비 수정은 입금 표시 API가 생긴 뒤에 붙인다 (ADR-042). 그때 두 테스트를 켠다
        @Test
        @Disabled("배달비 수정 단위에서 켠다 (ADR-042)")
        @DisplayName("아직 아무도 송금하지 않았으면 배달비를 다시 입력할 수 있다")
        void allowsRetype() throws Exception {
            startSettlement();

            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/delivery-fee")
                            .header("Authorization", bearer(host.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(feeBody(5_000)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.deliveryFee").value(5_000));

            // 정산표는 지우고 다시 만든다. 행이 쌓이지 않는다 (ADR-030)
            assertThat(settlementRepository.findByGroupOrderId(groupOrder.getId())).hasSize(2);
            assertThat(lineOf(member).getFeeShare()).isEqualTo(2_500);
        }

        @Test
        @Disabled("배달비 수정 단위에서 켠다 (ADR-042)")
        @DisplayName("한 명이라도 보냈다고 표시하면 배달비를 바꿀 수 없다")
        void rejectsRetypeAfterMarked() throws Exception {
            startSettlement();
            mockMvc.perform(post("/api/settlements/" + lineOf(member).getId() + "/mark-paid")
                    .header("Authorization", bearer(member.getId())));

            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/delivery-fee")
                            .header("Authorization", bearer(host.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(feeBody(5_000)))
                    .andExpect(status().isConflict());

            assertThat(lineOf(member).getFeeShare()).isEqualTo(1_750);
        }
    }

    @Nested
    @DisplayName("정산 정보 조회")
    class Find {

        @Test
        @DisplayName("방장은 전원의 줄과 자기 계좌를 본다")
        void hostSeesEveryone() throws Exception {
            startSettlement();

            mockMvc.perform(get("/api/group-orders/" + groupOrder.getId() + "/settlement")
                            .header("Authorization", bearer(host.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lines.length()").value(2))
                    .andExpect(jsonPath("$.hostAccount.bankName").value("한국은행"));
        }

        @Test
        @DisplayName("참여자는 자기 줄만 보고, 방장 계좌를 받는다")
        void memberSeesOwnLineOnly() throws Exception {
            startSettlement();

            mockMvc.perform(get("/api/group-orders/" + groupOrder.getId() + "/settlement")
                            .header("Authorization", bearer(member.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lines.length()").value(1))
                    .andExpect(jsonPath("$.lines[0].userId").value(member.getId()))
                    .andExpect(jsonPath("$.lines[0].totalAmount").value(MEMBER_MENU + 1_750))
                    .andExpect(jsonPath("$.hostAccount.accountNumber").value("110-123-456789"));
        }

        @Test
        @DisplayName("예금주는 마스킹해서 내려온다")
        void masksAccountHolder() throws Exception {
            startSettlement();

            // 은행 앱에 뜬 예금주와 대조해 계좌번호 오타를 잡을 만큼만 보여준다 (ADR-031)
            mockMvc.perform(get("/api/group-orders/" + groupOrder.getId() + "/settlement")
                            .header("Authorization", bearer(member.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hostAccount.accountHolder").value("김*수"));
        }

        @Test
        @DisplayName("참여하지 않은 사람에게는 없는 방이다")
        void hidesFromOutsider() throws Exception {
            startSettlement();

            // 여기엔 방장 계좌번호가 들어 있다. "있지만 못 본다"고 알려줄 이유가 없다
            mockMvc.perform(get("/api/group-orders/" + groupOrder.getId() + "/settlement")
                            .header("Authorization", bearer(outsider.getId())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("정산이 시작되기 전에는 볼 것이 없다")
        void rejectsBeforeSettling() throws Exception {
            closedRoom();

            mockMvc.perform(get("/api/group-orders/" + groupOrder.getId() + "/settlement")
                            .header("Authorization", bearer(member.getId())))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("로그인하지 않으면 계좌를 볼 수 없다")
        void requiresLogin() throws Exception {
            startSettlement();

            mockMvc.perform(get("/api/group-orders/" + groupOrder.getId() + "/settlement"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("입금 표시와 확인")
    class Payment {

        @Test
        @DisplayName("참여자가 보냈다고 표시하면 시각이 남는다")
        void marksPaid() throws Exception {
            startSettlement();

            mockMvc.perform(post("/api/settlements/" + lineOf(member).getId() + "/mark-paid")
                            .header("Authorization", bearer(member.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.markedPaidAt").isNotEmpty())
                    .andExpect(jsonPath("$.confirmedPaidAt").isEmpty());

            assertThat(lineOf(member).isDisputed()).isTrue();
        }

        @Test
        @DisplayName("남의 줄에는 표시할 수 없다")
        void rejectsMarkingOthersLine() throws Exception {
            startSettlement();

            mockMvc.perform(post("/api/settlements/" + lineOf(member).getId() + "/mark-paid")
                            .header("Authorization", bearer(outsider.getId())))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("방장이 확인하면 정리된다")
        void confirms() throws Exception {
            startSettlement();

            mockMvc.perform(post("/api/settlements/" + lineOf(member).getId() + "/confirm")
                            .header("Authorization", bearer(host.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.confirmedPaidAt").isNotEmpty());

            assertThat(lineOf(member).isConfirmed()).isTrue();
        }

        @Test
        @DisplayName("참여자가 스스로 확인할 수는 없다")
        void rejectsSelfConfirm() throws Exception {
            startSettlement();

            mockMvc.perform(post("/api/settlements/" + lineOf(member).getId() + "/confirm")
                            .header("Authorization", bearer(member.getId())))
                    .andExpect(status().isConflict());

            assertThat(lineOf(member).isConfirmed()).isFalse();
        }

        @Test
        @DisplayName("없는 정산 줄이면 404다")
        void rejectsUnknownSettlement() throws Exception {
            startSettlement();

            mockMvc.perform(post("/api/settlements/999999/confirm")
                            .header("Authorization", bearer(host.getId())))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("주문완료와 배달완료")
    class Transitions {

        @Test
        @DisplayName("전원 확인이 끝나면 주문완료로 넘어간다")
        void ordersWhenAllConfirmed() throws Exception {
            startSettlement();
            mockMvc.perform(post("/api/settlements/" + lineOf(member).getId() + "/confirm")
                    .header("Authorization", bearer(host.getId())));

            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/order")
                            .header("Authorization", bearer(host.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ORDERED"));
        }

        @Test
        @DisplayName("확인 안 된 사람이 남아 있으면 주문할 수 없다")
        void rejectsOrderWhenUnconfirmed() throws Exception {
            startSettlement();

            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/order")
                            .header("Authorization", bearer(host.getId())))
                    .andExpect(status().isConflict());

            assertThat(groupOrderRepository.findById(groupOrder.getId()).orElseThrow()
                    .getStatus().name()).isEqualTo("SETTLING");
        }

        @Test
        @DisplayName("주문완료 뒤에 배달완료로 넘어간다")
        void delivers() throws Exception {
            startSettlement();
            mockMvc.perform(post("/api/settlements/" + lineOf(member).getId() + "/confirm")
                    .header("Authorization", bearer(host.getId())));
            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/order")
                    .header("Authorization", bearer(host.getId())));

            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/deliver")
                            .header("Authorization", bearer(host.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DELIVERED"));
        }

        @Test
        @DisplayName("정산중에서 배달완료로 건너뛸 수 없다")
        void rejectsDeliverFromSettling() throws Exception {
            startSettlement();

            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/deliver")
                            .header("Authorization", bearer(host.getId())))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("참여자는 주문완료로 넘길 수 없다")
        void rejectsNonHostOrder() throws Exception {
            startSettlement();
            mockMvc.perform(post("/api/settlements/" + lineOf(member).getId() + "/confirm")
                    .header("Authorization", bearer(host.getId())));

            mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/order")
                            .header("Authorization", bearer(member.getId())))
                    .andExpect(status().isConflict());
        }
    }
}
