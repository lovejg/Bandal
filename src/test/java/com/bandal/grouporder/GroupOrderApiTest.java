package com.bandal.grouporder;

import com.bandal.settlement.SettlementRepository;
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
    SettlementRepository settlementRepository;

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
        // 정산 행이 group_order를 참조하므로 방보다 먼저 지운다
        settlementRepository.deleteAll();
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
        // 계좌가 없으면 방을 만들 수 없다 (ADR-031). 계좌 자체는 SettlementApiTest에서 본다
        ReflectionTestUtils.setField(user, "bankName", "한국은행");
        ReflectionTestUtils.setField(user, "accountNumber", "110-123-456789");
        ReflectionTestUtils.setField(user, "accountHolder", "김민수");
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
    @DisplayName("방 조회에는 로그인이 필요하다")
    void requiresLoginToRead() throws Exception {
        GroupOrder groupOrder = givenRoomWithTwoPeopleAndMenu();

        // 목록만 막고 단건을 열어두면 id를 1, 2, 3 ... 으로 훑어서 우회된다 (ADR-034)
        mockMvc.perform(get("/api/group-orders/" + groupOrder.getId()))
                .andExpect(status().isUnauthorized());
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
        mockMvc.perform(get("/api/group-orders/abc")
                        .header("Authorization", bearer(member.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("없는 주소를 부르면 404이고 우리 모양으로 답한다")
    void returnsNotFoundForUnknownPath() throws Exception {
        mockMvc.perform(get("/api/nope")
                        .header("Authorization", bearer(member.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("비로그인으로 없는 주소를 부르면 401이다. 주소가 있는지조차 알려주지 않는다")
    void hidesUnknownPathFromAnonymous() throws Exception {
        mockMvc.perform(get("/api/nope"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("허용하지 않는 메서드로 부르면 405다")
    void rejectsWrongMethod() throws Exception {
        GroupOrder groupOrder = givenRoomWithTwoPeopleAndMenu();

        mockMvc.perform(get("/api/group-orders/" + groupOrder.getId() + "/close")
                        .header("Authorization", bearer(member.getId())))
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
    @DisplayName("다른 학교 거점을 고르면 400이고 방이 생기지 않는다")
    void rejectsOtherUniversityPickupSpot() throws Exception {
        // 거점은 있다. 내 학교 것이 아닐 뿐이다. 거점이 있는지만 보면 통과해 버린다 (ADR-036)
        University minguk = universityRepository.save(new University("민국대학교", "minguk.ac.kr"));
        PickupSpot mingukSpot = pickupSpotRepository.save(new PickupSpot(minguk, "민국대 정문", null));
        CreateGroupOrderRequest request = new CreateGroupOrderRequest(
                mingukSpot.getId(), "○○마라탕", 15_000L, Instant.now().plus(2, ChronoUnit.HOURS), 4);

        mockMvc.perform(post("/api/group-orders")
                        .header("Authorization", bearer(host.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        assertThat(groupOrderRepository.count()).isZero();
    }

    @Test
    @DisplayName("방을 조회하면 현재 인원과 메뉴 합계가 같이 나온다")
    void findsGroupOrder() throws Exception {
        GroupOrder groupOrder = givenRoomWithTwoPeopleAndMenu();

        mockMvc.perform(get("/api/group-orders/" + groupOrder.getId())
                        .header("Authorization", bearer(member.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(groupOrder.getId()))
                .andExpect(jsonPath("$.participantCount").value(2))
                .andExpect(jsonPath("$.menuTotalAmount").value(20_000))
                .andExpect(jsonPath("$.cancelType").isEmpty());
    }

    @Test
    @DisplayName("참여한 사람이 방을 보면 자기 참여 id가 같이 나온다")
    void showsMyParticipationId() throws Exception {
        // 앱을 껐다 켜도, 다른 기기로 열어도 서버가 매번 알려준다 (ADR-039)
        GroupOrder groupOrder = givenRoomWithTwoPeopleAndMenu();
        Participation mine = participationRepository
                .findByGroupOrderIdAndUserId(groupOrder.getId(), member.getId()).orElseThrow();

        mockMvc.perform(get("/api/group-orders/" + groupOrder.getId())
                        .header("Authorization", bearer(member.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myParticipationId").value(mine.getId()));
    }

    @Test
    @DisplayName("같은 방이라도 보는 사람마다 자기 참여 id가 나온다")
    void myParticipationIdDependsOnViewer() throws Exception {
        GroupOrder groupOrder = givenRoomWithTwoPeopleAndMenu();
        Participation hostParticipation = participationRepository
                .findByGroupOrderIdAndUserId(groupOrder.getId(), host.getId()).orElseThrow();

        mockMvc.perform(get("/api/group-orders/" + groupOrder.getId())
                        .header("Authorization", bearer(host.getId())))
                .andExpect(jsonPath("$.myParticipationId").value(hostParticipation.getId()));
    }

    @Test
    @DisplayName("참여 안 한 사람이 방을 보면 참여 id는 비어 있다")
    void myParticipationIdIsNullForOutsider() throws Exception {
        // 프론트는 이걸 보고 "참여하기" 버튼을 띄운다
        GroupOrder groupOrder = givenRoomWithTwoPeopleAndMenu();
        User viewer = verifiedUser(university, "park@hankuk.ac.kr", "구경꾼");

        mockMvc.perform(get("/api/group-orders/" + groupOrder.getId())
                        .header("Authorization", bearer(viewer.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myParticipationId").isEmpty());
    }

    @Test
    @DisplayName("방을 만든 응답에도 방장의 참여 id가 나온다")
    void createReturnsHostParticipationId() throws Exception {
        // 방장은 만들자마자 자기 메뉴를 담는다. 방 상세를 다시 부르지 않아도 되게 한다
        String body = mockMvc.perform(post("/api/group-orders")
                        .header("Authorization", bearer(host.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        GroupOrder saved = groupOrderRepository.findAll().getFirst();
        Participation hostParticipation = participationRepository
                .findByGroupOrderIdAndUserId(saved.getId(), host.getId()).orElseThrow();

        assertThat(objectMapper.readTree(body).get("myParticipationId").asLong())
                .isEqualTo(hostParticipation.getId());
    }

    @Test
    @DisplayName("없는 방을 조회하면 404다")
    void returnsNotFoundForUnknownGroupOrder() throws Exception {
        mockMvc.perform(get("/api/group-orders/999999")
                        .header("Authorization", bearer(member.getId())))
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
                .andExpect(jsonPath("$.status").value("CLOSED"))
                // 마감 직후 방장 화면은 방 상세와 같은 모양으로 그린다 (ADR-040)
                .andExpect(jsonPath("$.participantCount").value(2))
                .andExpect(jsonPath("$.menuTotalAmount").value(20_000));

        assertThat(groupOrderRepository.findById(groupOrder.getId()).orElseThrow().getStatus())
                .isEqualTo(GroupOrderStatus.CLOSED);
    }

    @Test
    @DisplayName("최소주문금액이 모자라면 409이고 방은 모집중으로 남는다")
    void rejectsCloseWhenShortAmount() throws Exception {
        GroupOrder groupOrder = givenRoomWithTwoPeopleAndMenu();
        ReflectionTestUtils.setField(groupOrder, "minOrderAmount", 25_000L);
        groupOrderRepository.save(groupOrder);

        mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/close")
                        .header("Authorization", bearer(host.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("최소주문금액에 5000원 모자랍니다"));

        assertThat(groupOrderRepository.findById(groupOrder.getId()).orElseThrow().getStatus())
                .isEqualTo(GroupOrderStatus.RECRUITING);
    }

    @Test
    @DisplayName("마감 시각이 지난 방도 조건을 채웠으면 마감된다")
    void closesAfterDeadline() throws Exception {
        GroupOrder groupOrder = givenLateRoom(20_000);

        mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/close")
                        .header("Authorization", bearer(host.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.cancelType").isEmpty());
    }

    @Test
    @DisplayName("마감 시각이 지난 방이 금액을 못 채웠으면 200이고 자동 취소가 저장된다")
    void cancelsAfterDeadlineWhenShortAmount() throws Exception {
        // 채울 길이 없는 금액으로 거절하면 방이 영원히 모집중이다. 자동 마감 규칙대로 취소한다 (ADR-040)
        GroupOrder groupOrder = givenLateRoom(12_000);

        mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/close")
                        .header("Authorization", bearer(host.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.cancelType").value("DEADLINE_UNMET"))
                .andExpect(jsonPath("$.cancelReason").isNotEmpty());

        // 409로 알리려고 예외를 던졌다면 롤백돼서 여기가 모집중으로 남는다
        GroupOrder saved = groupOrderRepository.findById(groupOrder.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(GroupOrderStatus.CANCELED);
        assertThat(saved.getCancelType()).isEqualTo(CancelType.DEADLINE_UNMET);
    }

    @Test
    @DisplayName("마감 시각이 지났어도 방장이 아닌 사람은 409이고 방은 그대로다")
    void rejectsCloseByNonHostAfterDeadline() throws Exception {
        GroupOrder groupOrder = givenLateRoom(12_000);

        mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/close")
                        .header("Authorization", bearer(member.getId())))
                .andExpect(status().isConflict());

        GroupOrder saved = groupOrderRepository.findById(groupOrder.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(GroupOrderStatus.RECRUITING);
        assertThat(saved.getCancelType()).isNull();
    }

    @Test
    @DisplayName("이미 마감된 방을 다시 마감하면 409다")
    void rejectsCloseTwice() throws Exception {
        GroupOrder groupOrder = givenRoomWithTwoPeopleAndMenu();

        mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/close")
                        .header("Authorization", bearer(host.getId())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/group-orders/" + groupOrder.getId() + "/close")
                        .header("Authorization", bearer(host.getId())))
                .andExpect(status().isConflict());
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

        orderItemRepository.save(hostParticipation.addItem(host.getId(), "꿔바로우", "소", 11_000, 1, Instant.now()));
        orderItemRepository.save(memberParticipation.addItem(member.getId(), "마라탕", "2단계", 9_000, 1, Instant.now()));
        return groupOrder;
    }

    // 마감 시각이 30분 지났는데 스케줄러가 없어서 아직 모집중인 방. 방장과 참여자 한 명, 최소주문금액 15,000원
    // 메뉴는 마감 전에 담았다. 담는 시각을 마감 1분 전으로 넘겨서 만든다
    GroupOrder givenLateRoom(long memberMenuPrice) {
        Instant deadline = Instant.now().minus(30, ChronoUnit.MINUTES);
        Instant beforeDeadline = deadline.minus(1, ChronoUnit.MINUTES);
        GroupOrder groupOrder = groupOrderRepository.save(
                new GroupOrder(host, pickupSpot, "○○마라탕", 15_000L, deadline, 4));
        participationRepository.save(new Participation(groupOrder, host, beforeDeadline));
        Participation memberParticipation =
                participationRepository.save(new Participation(groupOrder, member, beforeDeadline));

        orderItemRepository.save(
                memberParticipation.addItem(member.getId(), "마라탕", null, memberMenuPrice, 1, beforeDeadline));
        return groupOrder;
    }
}
