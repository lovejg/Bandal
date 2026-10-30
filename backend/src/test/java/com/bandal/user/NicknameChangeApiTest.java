package com.bandal.user;

import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.JwtProvider;
import com.bandal.grouporder.CancelType;
import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.grouporder.GroupOrderStatus;
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
import com.jayway.jsonpath.JsonPath;
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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 닉네임 변경: 진행 중인 방이 있으면 막고, 7일에 한 번, 남이 쓰는 이름은 안 된다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NicknameChangeApiTest {

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

    University hankuk;
    PickupSpot pickupSpot;
    User jisu;
    User host;

    @BeforeEach
    void setUp() {
        settlementRepository.deleteAll();
        orderItemRepository.deleteAll();
        participationRepository.deleteAll();
        groupOrderRepository.deleteAll();
        userRepository.deleteAll();
        pickupSpotRepository.deleteAll();
        universityRepository.deleteAll();

        hankuk = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        pickupSpot = pickupSpotRepository.save(new PickupSpot(hankuk, "제1기숙사 로비", null));
        jisu = saveUser("jisu@hankuk.ac.kr", "마라탕러버");
        host = saveUser("host@hankuk.ac.kr", "방장님");
    }

    // 인증을 마치고 계좌도 있는 사용자(방장이 될 수 있다)
    User saveUser(String email, String nickname) {
        User user = new User(hankuk, email, "hash", nickname);
        ReflectionTestUtils.setField(user, "emailVerifiedAt", Instant.now());
        ReflectionTestUtils.setField(user, "bankName", "한국은행");
        ReflectionTestUtils.setField(user, "accountNumber", "110123456789");
        ReflectionTestUtils.setField(user, "accountHolder", "김민수");
        return userRepository.save(user);
    }

    // 원하는 상태의 방을 바로 만든다. 상태를 차례로 넘기는 과정은 여기서 보려는 게 아니다
    GroupOrder roomOf(User roomHost, GroupOrderStatus status) {
        GroupOrder room = new GroupOrder(roomHost, pickupSpot, "○○마라탕", 15000,
                Instant.now().plus(2, ChronoUnit.HOURS), 4);
        ReflectionTestUtils.setField(room, "status", status);
        return groupOrderRepository.save(room);
    }

    void join(GroupOrder room, User user) {
        participationRepository.save(new Participation(room, user, Instant.now()));
    }

    ResultActions change(User user, String nickname) throws Exception {
        return mockMvc.perform(patch("/api/users/me/nickname")
                .header("Authorization", "Bearer " + jwtProvider.createAccessToken(user.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"nickname": "%s"}
                        """.formatted(nickname)));
    }

    String nicknameOf(User user) {
        return userRepository.findById(user.getId()).orElseThrow().getNickname();
    }

    @Test
    @DisplayName("바꾸면 내 정보에 새 닉네임과 다음에 바꿀 수 있는 때(7일 뒤)가 나온다")
    void changesNickname() throws Exception {
        change(jisu, "떡볶이장인").andExpect(status().isNoContent());

        String body = mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + jwtProvider.createAccessToken(jisu.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("떡볶이장인"))
                .andReturn().getResponse().getContentAsString();

        String changeableAt = JsonPath.read(body, "$.nicknameChangeableAt");
        Duration untilNext = Duration.between(Instant.now(), Instant.parse(changeableAt));
        assertThat(untilNext).isBetween(Duration.ofDays(7).minusMinutes(1), Duration.ofDays(7));
    }

    @Test
    @DisplayName("한 번도 안 바꿨으면 다음에 바꿀 수 있는 때는 비어 있다(지금 바로 바꿀 수 있다)")
    void neverChangedHasNoWaiting() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + jwtProvider.createAccessToken(jisu.getId())))
                .andExpect(jsonPath("$.nicknameChangeableAt").doesNotExist());
    }

    @Test
    @DisplayName("다른 사람이 쓰는 닉네임이면 409다")
    void takenNickname() throws Exception {
        change(jisu, "방장님")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 쓰이고 있는 닉네임입니다"));
    }

    @Test
    @DisplayName("지금 닉네임과 같으면 400이고, 7일 기회를 쓰지 않는다")
    void sameNickname() throws Exception {
        change(jisu, "마라탕러버").andExpect(status().isBadRequest());

        change(jisu, "떡볶이장인").andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("앞뒤 공백은 빼고 저장한다")
    void trims() throws Exception {
        change(jisu, "  떡볶이장인 ").andExpect(status().isNoContent());

        assertThat(nicknameOf(jisu)).isEqualTo("떡볶이장인");
    }

    @Test
    @DisplayName("바꾼 지 7일이 안 됐으면 409다")
    void onceInSevenDays() throws Exception {
        change(jisu, "떡볶이장인").andExpect(status().isNoContent());

        change(jisu, "김밥천사")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("닉네임은 7일에 한 번 바꿀 수 있습니다"));
        assertThat(nicknameOf(jisu)).isEqualTo("떡볶이장인");
    }

    @Test
    @DisplayName("바꾼 지 7일이 지나면 다시 바꿀 수 있다")
    void afterSevenDays() throws Exception {
        User user = userRepository.findById(jisu.getId()).orElseThrow();
        ReflectionTestUtils.setField(user, "nicknameChangedAt", Instant.now().minus(Duration.ofDays(7)).minusSeconds(1));
        userRepository.save(user);

        change(jisu, "김밥천사").andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("버린 닉네임은 다른 사람이 바로 쓸 수 있다")
    void oldNicknameIsFree() throws Exception {
        change(jisu, "떡볶이장인").andExpect(status().isNoContent());

        change(host, "마라탕러버").andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("끝나지 않은 방의 방장이면 못 바꾼다")
    void blockedWhileHostingUnfinishedRoom() throws Exception {
        roomOf(jisu, GroupOrderStatus.RECRUITING);

        change(jisu, "떡볶이장인")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("진행 중인 방이나 끝나지 않은 환불이 있어 닉네임을 바꿀 수 없습니다. 끝난 뒤에 바꿔주세요"));
        assertThat(nicknameOf(jisu)).isEqualTo("마라탕러버");
    }

    @Test
    @DisplayName("끝나지 않은 방에 참여 중이면 정산중이든 주문완료든 못 바꾼다")
    void blockedWhileJoinedUnfinishedRoom() throws Exception {
        for (GroupOrderStatus status : new GroupOrderStatus[]{
                GroupOrderStatus.RECRUITING, GroupOrderStatus.CLOSED, GroupOrderStatus.SETTLING, GroupOrderStatus.ORDERED}) {
            participationRepository.deleteAll();
            groupOrderRepository.deleteAll();
            join(roomOf(host, status), jisu);

            change(jisu, "떡볶이장인").andExpect(status().isConflict());
        }
    }

    @Test
    @DisplayName("배달완료나 취소된 방만 있으면 바꿀 수 있다")
    void finishedRoomsDoNotBlock() throws Exception {
        join(roomOf(host, GroupOrderStatus.DELIVERED), jisu);
        join(roomOf(host, GroupOrderStatus.CANCELED), jisu);
        roomOf(jisu, GroupOrderStatus.DELIVERED);

        change(jisu, "떡볶이장인").andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("정산중에 취소된 방에서 환불이 남았으면 못 바꾼다(방 상태는 취소여도)")
    void blockedWhileRefundPending() throws Exception {
        GroupOrder room = roomOf(host, GroupOrderStatus.CANCELED);
        ReflectionTestUtils.setField(room, "cancelType", CancelType.HOST_WHILE_SETTLING);
        groupOrderRepository.save(room);
        join(room, jisu);
        Settlement line = SettlementFixtures.line(room, jisu, false);
        ReflectionTestUtils.setField(line, "markedPaidAt", Instant.now());
        settlementRepository.save(line);

        change(jisu, "떡볶이장인").andExpect(status().isConflict());
    }

    @Test
    @DisplayName("다른 사람의 진행 중인 방은 상관없다")
    void othersRoomsDoNotBlock() throws Exception {
        roomOf(host, GroupOrderStatus.SETTLING);

        change(jisu, "떡볶이장인").andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("2자보다 짧으면 400이다")
    void tooShort() throws Exception {
        change(jisu, "a").andExpect(status().isBadRequest());
    }
}
