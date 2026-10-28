package com.bandal.notification;

import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.JwtProvider;
import com.bandal.grouporder.GroupOrderChangeKind;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.grouporder.GroupOrderStatus;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.settlement.SettlementRepository;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
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
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 알림함. 알림 줄은 worker 대신 리포지토리로 바로 넣는다
// 목록, 안 읽은 개수, 하나 읽음, 모두 읽음으로 묶었다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NotificationApiTest {

    static final String LIST = "/api/notifications";
    static final String UNREAD = "/api/notifications/unread-count";
    static final String READ_ALL = "/api/notifications/read-all";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    NotificationRepository notificationRepository;

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

    // 알림을 받은 사람
    User park;
    // 남의 알림을 건드려 보는 사람
    User lee;

    long eventSeq;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        settlementRepository.deleteAll();
        orderItemRepository.deleteAll();
        participationRepository.deleteAll();
        groupOrderRepository.deleteAll();
        userRepository.deleteAll();
        pickupSpotRepository.deleteAll();
        universityRepository.deleteAll();

        University university = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        park = user(university, "park@hankuk.ac.kr", "박");
        lee = user(university, "lee@hankuk.ac.kr", "이");
        eventSeq = 100;
    }

    // 읽음 표시는 POST라 메일 인증을 마친 사용자여야 한다
    User user(University university, String email, String nickname) {
        User user = new User(university, email, "hashed-password", nickname);
        ReflectionTestUtils.setField(user, "emailVerifiedAt", Instant.now());
        return userRepository.save(user);
    }

    // worker가 넣었을 알림 한 줄. 넣을 때마다 상태 변경 번호가 하나씩 커진다
    Notification arrive(User to, String storeName, GroupOrderStatus status) {
        return notificationRepository.save(new Notification(eventSeq++, to.getId(), 7L, storeName, status,
                null, null, Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    Notification reload(Notification n) {
        return notificationRepository.findById(n.getId()).orElseThrow();
    }

    ResultActions fetch(String path, User viewer) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", bearer(viewer)))
                .andExpect(status().isOk());
    }

    ResultActions read(Notification n, User viewer) throws Exception {
        return mockMvc.perform(post(LIST + "/" + n.getId() + "/read").header("Authorization", bearer(viewer)));
    }

    ResultActions readAll(Long upToId, User viewer) throws Exception {
        return mockMvc.perform(post(READ_ALL).header("Authorization", bearer(viewer))
                .contentType(MediaType.APPLICATION_JSON)
                .content(upToId == null ? "{}" : "{\"upToId\":" + upToId + "}"));
    }

    String bearer(User user) {
        return "Bearer " + jwtProvider.createAccessToken(user.getId());
    }

    @Test
    @DisplayName("로그인하지 않으면 볼 수 없다")
    void requiresLogin() throws Exception {
        mockMvc.perform(get(LIST)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(UNREAD)).andExpect(status().isUnauthorized());
    }

    @Nested
    @DisplayName("목록")
    class MyList {

        @Test
        @DisplayName("내 알림만 최신순으로 나온다")
        void onlyMineNewestFirst() throws Exception {
            arrive(park, "첫째", GroupOrderStatus.CLOSED);
            arrive(lee, "이의 알림", GroupOrderStatus.CLOSED);
            arrive(park, "둘째", GroupOrderStatus.SETTLING);

            fetch(LIST, park)
                    .andExpect(jsonPath("$.notifications[*].storeName").value(contains("둘째", "첫째")));
        }

        @Test
        @DisplayName("한 줄에 알림 id, 방, 가게 이름, 종류, 상태, 사유, 금액, 배달비, 시각, 읽었는지가 들어 있다")
        void carriesFields() throws Exception {
            Notification n = notificationRepository.save(new Notification(eventSeq++, park.getId(), 7L, "마라탕집",
                    GroupOrderChangeKind.DELIVERY_FEE_CHANGED, GroupOrderStatus.SETTLING, null, 12_000L, 4_000L,
                    Instant.parse("2026-10-27T03:00:00Z")));

            fetch(LIST, park)
                    .andExpect(jsonPath("$.notifications[0].id").value(n.getId()))
                    .andExpect(jsonPath("$.notifications[0].groupOrderId").value(7))
                    .andExpect(jsonPath("$.notifications[0].storeName").value("마라탕집"))
                    .andExpect(jsonPath("$.notifications[0].kind").value("DELIVERY_FEE_CHANGED"))
                    .andExpect(jsonPath("$.notifications[0].status").value("SETTLING"))
                    .andExpect(jsonPath("$.notifications[0].cancelReason").value(nullValue()))
                    .andExpect(jsonPath("$.notifications[0].amount").value(12_000))
                    .andExpect(jsonPath("$.notifications[0].deliveryFee").value(4_000))
                    .andExpect(jsonPath("$.notifications[0].occurredAt").value("2026-10-27T03:00:00Z"))
                    .andExpect(jsonPath("$.notifications[0].read").value(false));
        }

        @Test
        @DisplayName("before를 넣으면 그 알림보다 오래된 것부터 정해진 개수만큼 나오고, 다음이 있는지 알려준다")
        void pagesByCursor() throws Exception {
            for (int i = 1; i <= 5; i++) {
                arrive(park, "알림" + i, GroupOrderStatus.CLOSED);
            }

            String first = mockMvc.perform(get(LIST).param("size", "2").header("Authorization", bearer(park)))
                    .andExpect(jsonPath("$.notifications[*].storeName").value(contains("알림5", "알림4")))
                    .andExpect(jsonPath("$.hasNext").value(true))
                    .andReturn().getResponse().getContentAsString();
            Long lastId = JsonPath.parse(first).read("$.notifications[1].id", Long.class);

            mockMvc.perform(get(LIST).param("before", lastId.toString()).param("size", "2")
                            .header("Authorization", bearer(park)))
                    .andExpect(jsonPath("$.notifications[*].storeName").value(contains("알림3", "알림2")))
                    .andExpect(jsonPath("$.hasNext").value(true));
        }

        @Test
        @DisplayName("첫 묶음을 받은 뒤 새 알림이 와도 다음 묶음에 겹치거나 빠지는 줄이 없다")
        void newArrivalDoesNotShiftNextPage() throws Exception {
            // 페이지 번호로 나누면 여기서 "알림3"이 두 번 나온다
            for (int i = 1; i <= 4; i++) {
                arrive(park, "알림" + i, GroupOrderStatus.CLOSED);
            }
            String first = mockMvc.perform(get(LIST).param("size", "2").header("Authorization", bearer(park)))
                    .andReturn().getResponse().getContentAsString();
            Long lastId = JsonPath.parse(first).read("$.notifications[1].id", Long.class);

            arrive(park, "새 알림", GroupOrderStatus.SETTLING);

            mockMvc.perform(get(LIST).param("before", lastId.toString()).param("size", "2")
                            .header("Authorization", bearer(park)))
                    .andExpect(jsonPath("$.notifications[*].storeName").value(contains("알림2", "알림1")))
                    .andExpect(jsonPath("$.hasNext").value(false));
        }

        @Test
        @DisplayName("알림이 없으면 빈 목록이다")
        void empty() throws Exception {
            fetch(LIST, park)
                    .andExpect(jsonPath("$.notifications.length()").value(0))
                    .andExpect(jsonPath("$.hasNext").value(false));
        }
    }

    @Nested
    @DisplayName("안 읽은 개수")
    class UnreadCount {

        @Test
        @DisplayName("내 알림 중 안 읽은 것만 센다")
        void countsMyUnread() throws Exception {
            arrive(park, "안 읽음1", GroupOrderStatus.CLOSED);
            arrive(park, "안 읽음2", GroupOrderStatus.SETTLING);
            Notification done = arrive(park, "읽음", GroupOrderStatus.ORDERED);
            ReflectionTestUtils.setField(done, "readAt", Instant.now());
            notificationRepository.save(done);
            arrive(lee, "이의 알림", GroupOrderStatus.CLOSED);

            fetch(UNREAD, park).andExpect(jsonPath("$.count").value(2));
        }

        @Test
        @DisplayName("알림이 없으면 0이다")
        void zero() throws Exception {
            fetch(UNREAD, park).andExpect(jsonPath("$.count").value(0));
        }
    }

    @Nested
    @DisplayName("하나 읽음")
    class ReadOne {

        @Test
        @DisplayName("누르면 읽음이 되고 204다. 목록과 개수에 바로 반영된다")
        void marksRead() throws Exception {
            Notification n = arrive(park, "마라탕집", GroupOrderStatus.SETTLING);

            read(n, park).andExpect(status().isNoContent());

            assertThat(reload(n).getReadAt()).isNotNull();
            fetch(LIST, park).andExpect(jsonPath("$.notifications[0].read").value(true));
            fetch(UNREAD, park).andExpect(jsonPath("$.count").value(0));
        }

        @Test
        @DisplayName("이미 읽은 걸 다시 눌러도 204이고, 처음 읽은 시각이 그대로다")
        void readAgainKeepsFirstTime() throws Exception {
            Notification n = arrive(park, "마라탕집", GroupOrderStatus.SETTLING);
            read(n, park);
            Instant firstReadAt = reload(n).getReadAt();

            read(n, park).andExpect(status().isNoContent());

            assertThat(reload(n).getReadAt()).isEqualTo(firstReadAt);
        }

        @Test
        @DisplayName("남의 알림이면 404이고 그 알림은 안 읽음 그대로다")
        void othersNotificationIsNotFound() throws Exception {
            Notification parks = arrive(park, "마라탕집", GroupOrderStatus.SETTLING);

            read(parks, lee).andExpect(status().isNotFound());

            assertThat(reload(parks).getReadAt()).isNull();
        }

        @Test
        @DisplayName("없는 알림이면 404다")
        void missingIsNotFound() throws Exception {
            mockMvc.perform(post(LIST + "/999999/read").header("Authorization", bearer(park)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("하나를 읽어도 다른 알림은 안 읽음 그대로다")
        void touchesOnlyThatOne() throws Exception {
            Notification a = arrive(park, "가", GroupOrderStatus.CLOSED);
            Notification b = arrive(park, "나", GroupOrderStatus.SETTLING);

            read(a, park);

            assertThat(reload(b).getReadAt()).isNull();
        }
    }

    @Nested
    @DisplayName("모두 읽음")
    class ReadAll {

        @Test
        @DisplayName("본 것 중 가장 최신 알림까지 모두 읽음이 되고 204다")
        void marksAllUpTo() throws Exception {
            Notification a = arrive(park, "가", GroupOrderStatus.CLOSED);
            Notification b = arrive(park, "나", GroupOrderStatus.SETTLING);

            readAll(b.getId(), park).andExpect(status().isNoContent());

            assertThat(reload(a).getReadAt()).isNotNull();
            assertThat(reload(b).getReadAt()).isNotNull();
            fetch(UNREAD, park).andExpect(jsonPath("$.count").value(0));
        }

        @Test
        @DisplayName("목록을 본 뒤에 들어온 알림은 안 읽음으로 남는다")
        void keepsArrivalsAfterViewing() throws Exception {
            arrive(park, "가", GroupOrderStatus.CLOSED);
            Notification seen = arrive(park, "나", GroupOrderStatus.SETTLING);
            // 목록을 열고 "모두 읽음"을 누르기 전에 worker가 넣었다
            Notification arrived = arrive(park, "배달 완료", GroupOrderStatus.DELIVERED);

            readAll(seen.getId(), park).andExpect(status().isNoContent());

            assertThat(reload(arrived).getReadAt()).isNull();
            fetch(UNREAD, park).andExpect(jsonPath("$.count").value(1));
        }

        @Test
        @DisplayName("남의 알림은 건드리지 않는다")
        void leavesOthersAlone() throws Exception {
            Notification parks = arrive(park, "박의 알림", GroupOrderStatus.CLOSED);
            Notification lees = arrive(lee, "이의 알림", GroupOrderStatus.CLOSED);

            // 이가 박의 알림 id보다 큰 값을 넣어도 자기 것만 바뀐다
            readAll(lees.getId(), lee).andExpect(status().isNoContent());

            assertThat(reload(parks).getReadAt()).isNull();
            assertThat(reload(lees).getReadAt()).isNotNull();
        }

        @Test
        @DisplayName("이미 읽은 알림의 읽은 시각은 바뀌지 않는다")
        void keepsEarlierReadTime() throws Exception {
            Notification a = arrive(park, "가", GroupOrderStatus.CLOSED);
            Notification b = arrive(park, "나", GroupOrderStatus.SETTLING);
            read(a, park);
            Instant firstReadAt = reload(a).getReadAt();

            readAll(b.getId(), park);

            assertThat(reload(a).getReadAt()).isEqualTo(firstReadAt);
        }

        @Test
        @DisplayName("본 알림 id가 없으면 400이다")
        void requiresUpToId() throws Exception {
            readAll(null, park).andExpect(status().isBadRequest());
        }
    }
}
