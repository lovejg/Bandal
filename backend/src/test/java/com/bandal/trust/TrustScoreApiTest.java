package com.bandal.trust;

import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.JwtProvider;
import com.bandal.grouporder.CancelType;
import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.grouporder.GroupOrderStatus;
import com.bandal.grouporder.StatusChangedBy;
import com.bandal.outbox.GroupOrderStatusPayload;
import com.bandal.outbox.GroupOrderStatusPayload.Participant;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 신뢰도 점수를 쓰는 곳: 방 만들기 제한, 방 화면의 방장 점수, 내 변동 기록. 그리고 배달비 상한
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TrustScoreApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    TrustScoreService trustScoreService;

    @Autowired
    TrustScoreRepository trustScoreRepository;

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
        pickupSpot = pickupSpotRepository.save(new PickupSpot(hankuk, "제1기숙사 로비", null));
        host = saveUser(hankuk, "host@hankuk.ac.kr", "방장님");
        member = saveUser(hankuk, "lee@hankuk.ac.kr", "마라탕러버");
    }

    User saveUser(University university, String email, String nickname) {
        User user = new User(university, email, "hash", nickname);
        ReflectionTestUtils.setField(user, "emailVerifiedAt", Instant.now());
        ReflectionTestUtils.setField(user, "bankName", "한국은행");
        ReflectionTestUtils.setField(user, "accountNumber", "110123456789");
        ReflectionTestUtils.setField(user, "accountHolder", "김민수");
        return userRepository.save(user);
    }

    String bearer(User user) {
        return "Bearer " + jwtProvider.createAccessToken(user.getId());
    }

    void setScore(User user, int score) {
        User found = userRepository.findById(user.getId()).orElseThrow();
        ReflectionTestUtils.setField(found, "trustScore", score);
        userRepository.save(found);
    }

    ResultActions createRoom(User user) throws Exception {
        return mockMvc.perform(post("/api/group-orders")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"pickupSpotId": %d, "storeName": "○○마라탕", "minOrderAmount": 15000,
                         "deadlineAt": "%s", "capacity": 4, "categories": ["CHICKEN"]}
                        """.formatted(pickupSpot.getId(), Instant.now().plus(2, ChronoUnit.HOURS))));
    }

    @Test
    @DisplayName("신뢰도 점수가 30점보다 낮으면 방을 만들 수 없다(409)")
    void lowScoreCannotHost() throws Exception {
        setScore(host, 29);

        createRoom(host)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("신뢰도 점수가 30점보다 낮아 방을 만들 수 없습니다"));
    }

    @Test
    @DisplayName("30점이면 방을 만들 수 있다")
    void thirtyCanHost() throws Exception {
        setScore(host, 30);

        createRoom(host).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("방 화면에 방장의 신뢰도 점수가 나온다")
    void roomShowsHostScore() throws Exception {
        setScore(host, 64);
        String body = createRoom(host).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Long roomId = com.jayway.jsonpath.JsonPath.<Number>read(body, "$.id").longValue();

        mockMvc.perform(get("/api/group-orders/" + roomId).header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostTrustScore").value(64));
    }

    @Test
    @DisplayName("내 변동 기록은 최근 것부터 이유, 점수, 가게 이름과 함께 나온다")
    void myChanges() throws Exception {
        trustScoreService.record(1L, new GroupOrderStatusPayload(10L, 1L, "○○마라탕", host.getId(),
                GroupOrderStatus.ORDERED, GroupOrderStatus.DELIVERED, StatusChangedBy.HOST, null, null, 3000L,
                List.of(new Participant(member.getId(), 9000L, true)), Instant.parse("2026-11-01T12:00:00Z")));
        trustScoreService.record(2L, new GroupOrderStatusPayload(11L, 1L, "△△떡볶이", host.getId(),
                GroupOrderStatus.SETTLING, GroupOrderStatus.CANCELED, StatusChangedBy.SYSTEM, CancelType.SETTLING_TIMEOUT, null, 3000L,
                List.of(new Participant(host.getId(), 5000L, true), new Participant(member.getId(), 9000L, false)),
                Instant.parse("2026-11-02T12:00:00Z")));

        mockMvc.perform(get("/api/users/me/trust-score-changes").header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].reason").value("DID_NOT_PAY"))
                .andExpect(jsonPath("$[0].delta").value(-5))
                .andExpect(jsonPath("$[0].storeName").value("△△떡볶이"))
                .andExpect(jsonPath("$[1].reason").value("DELIVERED"));
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(member)))
                .andExpect(jsonPath("$.trustScore").value(47));
    }

    @Test
    @DisplayName("배달비는 10,000원까지다. 넘으면 400")
    void deliveryFeeCap() throws Exception {
        GroupOrder room = new GroupOrder(host, pickupSpot, "○○마라탕", 15000, Instant.now().plus(2, ChronoUnit.HOURS), 4);
        ReflectionTestUtils.setField(room, "status", GroupOrderStatus.CLOSED);
        room = groupOrderRepository.save(room);
        participationRepository.save(new Participation(room, host, Instant.now()));

        mockMvc.perform(post("/api/group-orders/" + room.getId() + "/delivery-fee")
                        .header("Authorization", bearer(host))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deliveryFee": 40000}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("deliveryFee: 배달비는 10,000원까지 적을 수 있습니다"));
    }
}
