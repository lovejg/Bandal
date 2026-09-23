package com.bandal.grouporder;

import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.JwtProvider;
import com.bandal.grouporder.dto.CreateGroupOrderRequest;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.Participation;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpot;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제로 앱을 띄우고 HTTP로 부른다. 컨트롤러, 서비스, 엔티티, DB가 전부 엮여 돈다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GroupOrderApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    GroupOrderRepository groupOrderRepository;

    @Autowired
    ParticipationRepository participationRepository;

    @Autowired
    OrderItemRepository orderItemRepository;

    @Autowired
    UniversityRepository universityRepository;

    @Autowired
    PickupSpotRepository pickupSpotRepository;

    @Autowired
    UserRepository userRepository;

    University university;
    PickupSpot pickupSpot;
    User host;
    User member;

    @BeforeEach
    void setUp() {
        // 테스트끼리 데이터가 섞이지 않게 비우고 시작한다. 자식 테이블부터 지운다
        orderItemRepository.deleteAll();
        participationRepository.deleteAll();
        groupOrderRepository.deleteAll();
        userRepository.deleteAll();
        pickupSpotRepository.deleteAll();
        universityRepository.deleteAll();

        university = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        pickupSpot = pickupSpotRepository.save(new PickupSpot(university, "제1기숙사 로비", null));
        host = verifiedUser(university, "kim@hankuk.ac.kr", "배고파");
        member = verifiedUser(university, "lee@hankuk.ac.kr", "마라탕러버");
    }

    // 메일 인증을 마친 사용자. 미인증이면 쓰기가 전부 403이라 방 로직을 볼 수 없다 (ADR-027).
    // 인증 자체는 EmailVerificationApiTest에서 본다
    User verifiedUser(University university, String email, String nickname) {
        User user = new User(university, email, "hashed-password", nickname);
        ReflectionTestUtils.setField(user, "emailVerifiedAt", Instant.now());
        return userRepository.save(user);
    }

    // 로그인한 척하는 헤더. 이제 id를 직접 적을 수 없고 토큰을 만들어야 한다
    String bearer(Long userId) {
        return "Bearer " + jwtProvider.createAccessToken(userId);
    }

    CreateGroupOrderRequest validRequest() {
        return new CreateGroupOrderRequest(
                pickupSpot.getId(), "○○마라탕", 15_000L,
                Instant.now().plus(2, ChronoUnit.HOURS), 4);
    }

    @Test
    @DisplayName("방을 만들면 201과 만들어진 방이 돌아온다")
    void createsGroupOrder() throws Exception {
        mockMvc.perform(post("/api/group-orders")
                        .header("Authorization", bearer(host.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.hostId").value(host.getId()))
                .andExpect(jsonPath("$.pickupSpotId").value(pickupSpot.getId()))
                .andExpect(jsonPath("$.storeName").value("○○마라탕"))
                .andExpect(jsonPath("$.status").value("RECRUITING"))
                .andExpect(jsonPath("$.participantCount").value(1))
                .andExpect(jsonPath("$.menuTotalAmount").value(0));
    }

    @Test
    @DisplayName("응답에 방장의 비밀번호나 이메일이 섞여 나가지 않는다")
    void doesNotLeakUserFields() throws Exception {
        String body = mockMvc.perform(post("/api/group-orders")
                        .header("Authorization", bearer(host.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("hashed-password");
        assertThat(body).doesNotContain("kim@hankuk.ac.kr");
    }

    @Test
    @DisplayName("방을 만들면 방장의 참여 행도 함께 생긴다")
    void createsHostParticipation() throws Exception {
        mockMvc.perform(post("/api/group-orders")
                        .header("Authorization", bearer(host.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated());

        GroupOrder saved = groupOrderRepository.findAll().getFirst();
        assertThat(participationRepository.countByGroupOrderId(saved.getId())).isEqualTo(1);
        assertThat(participationRepository.existsByGroupOrderIdAndUserId(saved.getId(), host.getId())).isTrue();
    }

    @Test
    @DisplayName("가게 이름이 비면 400이고 이유가 돌아온다")
    void rejectsBlankStoreName() throws Exception {
        CreateGroupOrderRequest request = new CreateGroupOrderRequest(
                pickupSpot.getId(), "  ", 15_000L, Instant.now().plus(2, ChronoUnit.HOURS), 4);

        mockMvc.perform(post("/api/group-orders")
                        .header("Authorization", bearer(host.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("storeName")));
    }

    @Test
    @DisplayName("마감 시각이 이미 지났으면 400이다")
    void rejectsPastDeadline() throws Exception {
        CreateGroupOrderRequest request = new CreateGroupOrderRequest(
                pickupSpot.getId(), "○○마라탕", 15_000L, Instant.now().minusSeconds(60), 4);

        mockMvc.perform(post("/api/group-orders")
                        .header("Authorization", bearer(host.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("정원이 1명이면 400이다")
    void rejectsCapacityOfOne() throws Exception {
        CreateGroupOrderRequest request = new CreateGroupOrderRequest(
                pickupSpot.getId(), "○○마라탕", 15_000L, Instant.now().plus(2, ChronoUnit.HOURS), 1);

        mockMvc.perform(post("/api/group-orders")
                        .header("Authorization", bearer(host.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("토큰 없이 방을 만들려 하면 401이다")
    void rejectsMissingToken() throws Exception {
        mockMvc.perform(post("/api/group-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        assertThat(groupOrderRepository.count()).isZero();
    }

    @Test
    @DisplayName("아무렇게나 지어낸 토큰이면 401이다")
    void rejectsForgedToken() throws Exception {
        mockMvc.perform(post("/api/group-orders")
                        .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.forged")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());

        assertThat(groupOrderRepository.count()).isZero();
    }

    @Test
    @DisplayName("방 조회는 토큰 없이도 된다")
    void allowsAnonymousRead() throws Exception {
        GroupOrder groupOrder = givenRoomWithTwoPeopleAndMenu();

        mockMvc.perform(get("/api/group-orders/" + groupOrder.getId()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("숫자 필드가 빠졌으면 어느 필드인지 알려준다")
    void namesMissingNumericField() throws Exception {
        // 최소주문금액과 정원이 없는 본문
        String body = """
                {"pickupSpotId": %d, "storeName": "○○마라탕", "deadlineAt": "2026-12-30T10:00:00Z"}
                """.formatted(pickupSpot.getId());

        mockMvc.perform(post("/api/group-orders")
                        .header("Authorization", bearer(host.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("minOrderAmount")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("capacity")));
    }

    @Test
    @DisplayName("JSON이 깨졌으면 400이고 우리 모양으로 답한다")
    void rejectsBrokenJson() throws Exception {
        mockMvc.perform(post("/api/group-orders")
                        .header("Authorization", bearer(host.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeName\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("방 id 자리에 숫자가 아닌 게 오면 400이다")
    void rejectsNonNumericId() throws Exception {
        mockMvc.perform(get("/api/group-orders/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("없는 주소를 부르면 404이고 우리 모양으로 답한다")
    void returnsNotFoundForUnknownPath() throws Exception {
        mockMvc.perform(get("/api/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("허용하지 않는 메서드로 부르면 405다")
    void rejectsWrongMethod() throws Exception {
        GroupOrder groupOrder = givenRoomWithTwoPeopleAndMenu();

        mockMvc.perform(get("/api/group-orders/" + groupOrder.getId() + "/close"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    @DisplayName("없는 사용자의 토큰이면 401이다")
    void rejectsUnknownHost() throws Exception {
        // 탈퇴한 계정의 토큰이 아직 안 만료된 상황. 컨트롤러까지 가지 못하고 필터에서 걸린다.
        // "이메일 인증이 필요합니다"(403)는 인증할 계정조차 없는 사람에게 할 말이 아니다 (ADR-027)
        mockMvc.perform(post("/api/group-orders")
                        .header("Authorization", bearer(999_999L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다"));
    }

    @Test
    @DisplayName("없는 거점을 고르면 404다")
    void rejectsUnknownPickupSpot() throws Exception {
        CreateGroupOrderRequest request = new CreateGroupOrderRequest(
                999_999L, "○○마라탕", 15_000L, Instant.now().plus(2, ChronoUnit.HOURS), 4);

        mockMvc.perform(post("/api/group-orders")
                        .header("Authorization", bearer(host.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("방을 조회하면 현재 인원과 메뉴 합계가 같이 나온다")
    void findsGroupOrder() throws Exception {
        GroupOrder groupOrder = givenRoomWithTwoPeopleAndMenu();

        mockMvc.perform(get("/api/group-orders/" + groupOrder.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(groupOrder.getId()))
                .andExpect(jsonPath("$.participantCount").value(2))
                .andExpect(jsonPath("$.menuTotalAmount").value(20_000))
                .andExpect(jsonPath("$.cancelType").isEmpty());
    }

    @Test
    @DisplayName("없는 방을 조회하면 404다")
    void returnsNotFoundForUnknownGroupOrder() throws Exception {
        mockMvc.perform(get("/api/group-orders/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("방장이 마감하면 200이고 상태가 마감으로 바뀐다")
    void closesGroupOrder() throws Exception {
        GroupOrder groupOrder = givenRoomWithTwoPeopleAndMenu();

        mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/close")
                        .header("Authorization", bearer(host.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        assertThat(groupOrderRepository.findById(groupOrder.getId()).orElseThrow().getStatus())
                .isEqualTo(GroupOrderStatus.CLOSED);
    }

    @Test
    @DisplayName("혼자 있는 방을 마감하려 하면 409이고 방은 모집중으로 남는다")
    void rejectsCloseWhenAlone() throws Exception {
        GroupOrder groupOrder = groupOrderRepository.save(
                new GroupOrder(host, pickupSpot, "○○마라탕", 15_000L, Instant.now().plus(2, ChronoUnit.HOURS), 4));
        participationRepository.save(new Participation(groupOrder, host, Instant.now()));

        mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/close")
                        .header("Authorization", bearer(host.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        assertThat(groupOrderRepository.findById(groupOrder.getId()).orElseThrow().getStatus())
                .isEqualTo(GroupOrderStatus.RECRUITING);
    }

    @Test
    @DisplayName("방장이 아닌 사람이 마감하려 하면 409다")
    void rejectsCloseByNonHost() throws Exception {
        GroupOrder groupOrder = givenRoomWithTwoPeopleAndMenu();

        mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/close")
                        .header("Authorization", bearer(member.getId())))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("없는 방을 마감하려 하면 404다")
    void rejectsCloseOfUnknownGroupOrder() throws Exception {
        mockMvc.perform(post("/api/group-orders/999999/close")
                        .header("Authorization", bearer(host.getId())))
                .andExpect(status().isNotFound());
    }

    // 방장과 참여자 한 명, 메뉴 20,000원어치가 있는 방
    GroupOrder givenRoomWithTwoPeopleAndMenu() {
        GroupOrder groupOrder = groupOrderRepository.save(
                new GroupOrder(host, pickupSpot, "○○마라탕", 15_000L, Instant.now().plus(2, ChronoUnit.HOURS), 4));
        Participation hostParticipation =
                participationRepository.save(new Participation(groupOrder, host, Instant.now()));
        Participation memberParticipation =
                participationRepository.save(new Participation(groupOrder, member, Instant.now()));

        orderItemRepository.save(hostParticipation.addItem(host.getId(), "꿔바로우", "소", 11_000, 1));
        orderItemRepository.save(memberParticipation.addItem(member.getId(), "마라탕", "2단계", 9_000, 1));
        return groupOrder;
    }
}
