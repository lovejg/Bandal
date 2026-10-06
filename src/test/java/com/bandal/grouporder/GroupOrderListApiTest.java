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
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 방 목록. 참여할 방을 고르는 화면이다
// 조건: 내 학교 / 모집중 / 마감 시각 전 / 정원 남음. 정렬: 마감 임박순 (ADR-034)
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
// 쿼리 수를 세려고 하이버네이트 통계를 켠다. 맨 아래 N+1 테스트가 쓴다
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class GroupOrderListApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    EntityManagerFactory entityManagerFactory;

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
    // 목록을 보는 사람. 한국대 학생이다
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

        hankuk = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        minguk = universityRepository.save(new University("민국대학교", "minguk.ac.kr"));
        dorm = pickupSpotRepository.save(new PickupSpot(hankuk, "제1기숙사 로비", null));
        mingukSpot = pickupSpotRepository.save(new PickupSpot(minguk, "민국대 정문", null));

        userSeq = 0;
        viewer = user(hankuk, "구경꾼", true);
    }

    // 메일 인증과 계좌까지 마친 사용자. 방장이 되려면 계좌가 있어야 한다 (ADR-031)
    User user(University university, String nickname, boolean verified) {
        userSeq++;
        String domain = university == hankuk ? "hankuk.ac.kr" : "minguk.ac.kr";
        User user = new User(university, "user" + userSeq + "@" + domain, "hashed-password", nickname);
        if (verified) {
            ReflectionTestUtils.setField(user, "emailVerifiedAt", Instant.now());
        }
        ReflectionTestUtils.setField(user, "bankName", "한국은행");
        ReflectionTestUtils.setField(user, "accountNumber", "110123456789");
        ReflectionTestUtils.setField(user, "accountHolder", "김민수");
        return userRepository.save(user);
    }

    // 방을 만들고 방장의 참여 행과 메뉴 한 줄을 넣는다
    GroupOrder room(User host, PickupSpot spot, String storeName, long hostMenu,
                    Instant deadlineAt, int capacity) {
        GroupOrder room = groupOrderRepository.save(
                new GroupOrder(host, spot, storeName, 15_000, deadlineAt, capacity));
        Participation hostParticipation =
                participationRepository.save(new Participation(room, host, Instant.now()));
        orderItemRepository.save(hostParticipation.addItem(host.getId(), "메뉴", null, hostMenu, 1, deadlineAt.minusSeconds(60)));
        return room;
    }

    // 마감 시각을 간단히 쓰려고. 지금으로부터 몇 시간 뒤
    Instant inHours(long hours) {
        return Instant.now().plus(hours, ChronoUnit.HOURS);
    }

    String bearer(User user) {
        return "Bearer " + jwtProvider.createAccessToken(user.getId());
    }

    @Test
    @DisplayName("로그인하지 않으면 목록을 볼 수 없다")
    void requiresLogin() throws Exception {
        // 내 학교가 어디인지 알아야 거를 수 있다 (ADR-034)
        mockMvc.perform(get("/api/group-orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("내 학교 방만 나온다")
    void showsOnlyMyUniversity() throws Exception {
        room(user(hankuk, "한국방장", true), dorm, "○○마라탕", 8_000, inHours(2), 4);
        room(user(minguk, "민국방장", true), mingukSpot, "△△치킨", 8_000, inHours(2), 4);

        mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms.length()").value(1))
                .andExpect(jsonPath("$.rooms[0].storeName").value("○○마라탕"));
    }

    @Test
    @DisplayName("마감된 방은 나오지 않는다")
    void hidesClosedRoom() throws Exception {
        User host = user(hankuk, "방장", true);
        GroupOrder closed = room(host, dorm, "마감된 방", 20_000, inHours(2), 4);
        User member = user(hankuk, "참여자", true);
        participationRepository.save(new Participation(closed, member, Instant.now()));
        closed.closeByHost(host.getId(), 2, 20_000, Instant.now());
        groupOrderRepository.save(closed);

        room(user(hankuk, "다른방장", true), dorm, "모집중인 방", 8_000, inHours(2), 4);

        mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.rooms.length()").value(1))
                .andExpect(jsonPath("$.rooms[0].storeName").value("모집중인 방"));
    }

    @Test
    @DisplayName("마감 시각이 지났는데 아직 모집중으로 남은 방은 나오지 않는다")
    void hidesExpiredRoom() throws Exception {
        // closeAtDeadline을 부르는 스케줄러가 아직 없어서 이런 방이 실제로 남는다
        room(user(hankuk, "방장", true), dorm, "지난 방", 8_000, Instant.now().minus(1, ChronoUnit.HOURS), 4);
        room(user(hankuk, "다른방장", true), dorm, "살아있는 방", 8_000, inHours(2), 4);

        mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.rooms.length()").value(1))
                .andExpect(jsonPath("$.rooms[0].storeName").value("살아있는 방"));
    }

    @Test
    @DisplayName("정원이 다 찬 방은 나오지 않는다")
    void hidesFullRoom() throws Exception {
        // 정원이 차도 마감이 되는 건 아니다. 최소주문금액을 못 채웠으면 여전히 모집중이다.
        // 그래도 더 받을 수 없으니 목록에서는 뺀다
        GroupOrder full = room(user(hankuk, "방장", true), dorm, "꽉 찬 방", 8_000, inHours(2), 2);
        participationRepository.save(new Participation(full, user(hankuk, "참여자", true), Instant.now()));

        room(user(hankuk, "다른방장", true), dorm, "자리 있는 방", 8_000, inHours(2), 4);

        mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.rooms.length()").value(1))
                .andExpect(jsonPath("$.rooms[0].storeName").value("자리 있는 방"));
    }

    @Test
    @DisplayName("마감이 가까운 순서로 나온다")
    void sortsByDeadline() throws Exception {
        room(user(hankuk, "방장1", true), dorm, "세 시간 뒤", 8_000, inHours(3), 4);
        room(user(hankuk, "방장2", true), dorm, "한 시간 뒤", 8_000, inHours(1), 4);
        room(user(hankuk, "방장3", true), dorm, "두 시간 뒤", 8_000, inHours(2), 4);

        mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.rooms[*].storeName")
                        .value(contains("한 시간 뒤", "두 시간 뒤", "세 시간 뒤")));
    }

    @Test
    @DisplayName("한 줄에 방을 고르는 데 필요한 정보가 다 들어 있다")
    void carriesSummaryFields() throws Exception {
        User host = user(hankuk, "배고파", true);
        GroupOrder room = room(host, dorm, "○○마라탕", 8_000, inHours(2), 4);
        Participation member = participationRepository.save(
                new Participation(room, user(hankuk, "마라탕러버", true), Instant.now()));
        orderItemRepository.save(member.addItem(member.getUser().getId(), "마라탕 소", null, 9_000, 1, Instant.now()));

        mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.rooms[0].id").value(room.getId()))
                .andExpect(jsonPath("$.rooms[0].storeName").value("○○마라탕"))
                .andExpect(jsonPath("$.rooms[0].pickupSpotId").value(dorm.getId()))
                .andExpect(jsonPath("$.rooms[0].pickupSpotName").value("제1기숙사 로비"))
                .andExpect(jsonPath("$.rooms[0].hostNickname").value("배고파"))
                .andExpect(jsonPath("$.rooms[0].minOrderAmount").value(15_000))
                // 8,000 + 9,000. 화면에는 "17,000 / 15,000"으로 보인다
                .andExpect(jsonPath("$.rooms[0].menuTotalAmount").value(17_000))
                .andExpect(jsonPath("$.rooms[0].participantCount").value(2))
                .andExpect(jsonPath("$.rooms[0].capacity").value(4))
                .andExpect(jsonPath("$.rooms[0].deadlineAt").isNotEmpty());
    }

    @Test
    @DisplayName("아무도 메뉴를 안 담은 방은 합계가 0이다")
    void emptyMenuIsZero() throws Exception {
        GroupOrder room = groupOrderRepository.save(new GroupOrder(
                user(hankuk, "방장", true), dorm, "빈 방", 15_000, inHours(2), 4));
        participationRepository.save(new Participation(room, room.getHost(), Instant.now()));

        mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.rooms[0].menuTotalAmount").value(0))
                .andExpect(jsonPath("$.rooms[0].participantCount").value(1));
    }

    @Test
    @DisplayName("정해진 개수만큼 나눠 주고 다음 페이지가 있는지 알려준다")
    void pages() throws Exception {
        room(user(hankuk, "방장1", true), dorm, "첫째", 8_000, inHours(1), 4);
        room(user(hankuk, "방장2", true), dorm, "둘째", 8_000, inHours(2), 4);
        room(user(hankuk, "방장3", true), dorm, "셋째", 8_000, inHours(3), 4);

        mockMvc.perform(get("/api/group-orders").param("page", "0").param("size", "2")
                        .header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.rooms[*].storeName").value(contains("첫째", "둘째")))
                .andExpect(jsonPath("$.hasNext").value(true));

        mockMvc.perform(get("/api/group-orders").param("page", "1").param("size", "2")
                        .header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.rooms[*].storeName").value(contains("셋째")))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("메일 인증을 안 한 사용자도 목록은 볼 수 있다")
    void unverifiedCanBrowse() throws Exception {
        // 구경은 허용한다. 막히는 건 쓰기뿐이다 (ADR-027)
        User unverified = user(hankuk, "신입생", false);
        room(user(hankuk, "방장", true), dorm, "○○마라탕", 8_000, inHours(2), 4);

        mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(unverified)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms.length()").value(1));
    }

    @Test
    @DisplayName("방이 늘어나도 쿼리 수는 늘지 않는다")
    void queryCountDoesNotGrowWithRooms() throws Exception {
        // 방마다 인원을 세고, 합계를 더하고, 방장 닉네임을 읽으면 방 수에 비례해 쿼리가 는다 (N+1).
        // 방장을 방마다 다르게 둔다. 같은 사람이면 한 번 읽은 걸 재사용해서 늘어나는 게 안 보인다
        for (int i = 0; i < 2; i++) {
            room(user(hankuk, "방장" + i, true), dorm, "방" + i, 8_000, inHours(i + 1), 4);
        }
        long withTwoRooms = countQueries();

        for (int i = 2; i < 10; i++) {
            room(user(hankuk, "방장" + i, true), dorm, "방" + i, 8_000, inHours(i + 1), 4);
        }
        long withTenRooms = countQueries();

        assertThat(withTenRooms)
                .as("방 2개일 때 %d번, 10개일 때 %d번. 방 수에 따라 늘면 N+1이다", withTwoRooms, withTenRooms)
                .isEqualTo(withTwoRooms);
    }

    // 목록을 한 번 부르는 동안 나간 SQL 수
    long countQueries() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        mockMvc.perform(get("/api/group-orders").param("size", "30")
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isOk());
        return statistics.getPrepareStatementCount();
    }
}
