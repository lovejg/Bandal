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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 방 목록 캐시. 학교별로 첫 페이지만 Redis에 10초 담는다
// DB를 서비스 없이 리포지토리로 직접 바꿔서, 응답이 DB에서 왔는지 캐시에서 왔는지 가린다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GroupOrderListCacheTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    SettlementRepository settlementRepository;

    @Autowired
    OrderItemRepository orderItemRepository;

    @Autowired
    ParticipationRepository participationRepository;

    @Autowired
    GroupOrderRepository groupOrderRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PickupSpotRepository pickupSpotRepository;

    @Autowired
    UniversityRepository universityRepository;

    University hankuk;
    University minguk;
    PickupSpot dorm;
    PickupSpot mingukSpot;
    User viewer;

    int userSeq;

    @BeforeEach
    void setUp() {
        settlementRepository.deleteAll();
        orderItemRepository.deleteAll();
        participationRepository.deleteAll();
        groupOrderRepository.deleteAll();
        userRepository.deleteAll();
        pickupSpotRepository.deleteAll();
        universityRepository.deleteAll();

        // 학교 id는 테스트마다 새로 늘어나서, 앞 테스트가 남긴 캐시 키와 겹치지 않는다
        hankuk = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        minguk = universityRepository.save(new University("민국대학교", "minguk.ac.kr"));
        dorm = pickupSpotRepository.save(new PickupSpot(hankuk, "제1기숙사 로비", null));
        mingukSpot = pickupSpotRepository.save(new PickupSpot(minguk, "민국대 정문", null));

        userSeq = 0;
        viewer = user(hankuk, "구경꾼");
    }

    User user(University university, String nickname) {
        userSeq++;
        String domain = university == hankuk ? "hankuk.ac.kr" : "minguk.ac.kr";
        User user = new User(university, "user" + userSeq + "@" + domain, "hashed-password", nickname);
        ReflectionTestUtils.setField(user, "emailVerifiedAt", Instant.now());
        ReflectionTestUtils.setField(user, "bankName", "한국은행");
        ReflectionTestUtils.setField(user, "accountNumber", "110123456789");
        ReflectionTestUtils.setField(user, "accountHolder", "김민수");
        return userRepository.save(user);
    }

    // 서비스를 거치지 않고 DB에 바로 넣는다. 캐시는 이 변화를 모른다
    GroupOrder room(User host, PickupSpot spot, String storeName, Instant deadlineAt) {
        GroupOrder room = groupOrderRepository.save(new GroupOrder(host, spot, storeName, 15_000, deadlineAt, 4));
        participationRepository.save(new Participation(room, host, Instant.now()));
        return room;
    }

    Instant inHours(long hours) {
        return Instant.now().plus(hours, ChronoUnit.HOURS);
    }

    String bearer(User user) {
        return "Bearer " + jwtProvider.createAccessToken(user.getId());
    }

    String key(University university) {
        return "rooms:" + university.getId();
    }

    @Test
    @DisplayName("기본 크기는 20이다")
    void defaultSizeIs20() throws Exception {
        for (int i = 0; i < 21; i++) {
            room(user(hankuk, "방장" + i), dorm, "방" + i, inHours(1 + i));
        }

        mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms.length()").value(20))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    @DisplayName("첫 페이지를 열면 학교 키로 담기고, 10초 뒤 사라지게 TTL이 붙는다")
    void firstPageIsCachedWithTtl() throws Exception {
        room(user(hankuk, "방장"), dorm, "○○마라탕", inHours(2));

        mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk());

        assertThat(redisTemplate.hasKey(key(hankuk))).isTrue();
        // 남은 수명(초). TTL이 없으면 -1이다
        assertThat(redisTemplate.getExpire(key(hankuk))).isBetween(1L, 10L);
    }

    @Test
    @DisplayName("담긴 동안에는 DB가 바뀌어도 담긴 목록이 나오고, 키를 지우면 DB에서 새로 만든다")
    void servesCachedUntilEvicted() throws Exception {
        room(user(hankuk, "방장"), dorm, "먼저 생긴 방", inHours(1));
        mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.rooms.length()").value(1));

        // 서비스를 거치지 않고 방을 하나 더 넣는다. 캐시는 이걸 모른다
        room(user(hankuk, "다른방장"), dorm, "나중에 생긴 방", inHours(2));

        mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.rooms.length()").value(1))
                .andExpect(jsonPath("$.rooms[0].storeName").value("먼저 생긴 방"));

        redisTemplate.delete(key(hankuk));

        mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.rooms.length()").value(2));
    }

    @Test
    @DisplayName("캐시에서 온 응답도 DB에서 만든 응답과 같은 모양이다")
    void cachedResponseKeepsShape() throws Exception {
        room(user(hankuk, "방장"), dorm, "○○마라탕", inHours(2));

        String fromDb = mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(viewer)))
                .andReturn().getResponse().getContentAsString();
        assertThat(redisTemplate.hasKey(key(hankuk))).isTrue();

        // 두 번째는 캐시에서 온다. JSON으로 넣었다 꺼내도 칸과 값(마감 시각 포함)이 그대로여야 한다
        String fromCache = mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(viewer)))
                .andReturn().getResponse().getContentAsString();

        assertThat(fromCache).isEqualTo(fromDb);
    }

    @Test
    @DisplayName("두 번째 페이지는 담지 않고 매번 DB에서 만든다")
    void secondPageIsNotCached() throws Exception {
        mockMvc.perform(get("/api/group-orders?page=1&size=20").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk());

        assertThat(redisTemplate.hasKey(key(hankuk))).isFalse();
    }

    @Test
    @DisplayName("크기가 20이 아니면 담지도 않고, 담긴 첫 페이지를 꺼내 쓰지도 않는다")
    void otherSizeBypassesCache() throws Exception {
        mockMvc.perform(get("/api/group-orders?page=0&size=30").header("Authorization", bearer(viewer)));
        assertThat(redisTemplate.hasKey(key(hankuk))).isFalse();

        room(user(hankuk, "방장"), dorm, "먼저 생긴 방", inHours(1));
        // 크기 20으로 담는다
        mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(viewer)));
        room(user(hankuk, "다른방장"), dorm, "나중에 생긴 방", inHours(2));

        // 크기 30은 담긴 20짜리를 쓰지 않고 DB로 간다
        mockMvc.perform(get("/api/group-orders?page=0&size=30").header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.rooms.length()").value(2));
    }

    @Test
    @DisplayName("학교마다 따로 담긴다. 한 학교의 캐시가 다른 학교 사람에게 나가지 않는다")
    void cachedPerUniversity() throws Exception {
        room(user(hankuk, "한국방장"), dorm, "한국대 방", inHours(1));
        room(user(minguk, "민국방장"), mingukSpot, "민국대 방", inHours(1));
        User mingukViewer = user(minguk, "민국구경꾼");

        mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.rooms[0].storeName").value("한국대 방"));
        mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(mingukViewer)))
                .andExpect(jsonPath("$.rooms.length()").value(1))
                .andExpect(jsonPath("$.rooms[0].storeName").value("민국대 방"));

        assertThat(redisTemplate.hasKey(key(hankuk))).isTrue();
        assertThat(redisTemplate.hasKey(key(minguk))).isTrue();
    }

    @Test
    @DisplayName("같은 학교면 다른 사람이 연 목록도 같은 캐시에서 나온다")
    void sharedWithinUniversity() throws Exception {
        room(user(hankuk, "방장"), dorm, "먼저 생긴 방", inHours(1));
        mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(viewer)));

        room(user(hankuk, "다른방장"), dorm, "나중에 생긴 방", inHours(2));

        // 다른 한국대 학생. 사람마다 캐시가 따로라면 DB에서 2개를 받는다
        mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(user(hankuk, "다른구경꾼"))))
                .andExpect(jsonPath("$.rooms.length()").value(1));
    }
}
