package com.bandal.grouporder;

import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.JwtProvider;
import com.bandal.participation.OrderItem;
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
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 방 목록을 바꾸는 행동마다 그 학교의 캐시가 지워지는지
// 모두 같은 순서다: 목록을 열어 캐시에 담는다 -> 행동한다 -> 키가 없어졌는지, 다시 열면 바뀐 게 보이는지
// 지우지 않으면 TTL 10초 동안 옛 목록이 나와서, 다시 연 목록이 바뀌지 않는다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GroupOrderListEvictionTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    GroupOrderExpiryScheduler scheduler;

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
    PickupSpot dorm;
    User viewer;
    User host;
    User member;

    // 방장 메뉴 10,000원, 참여자 메뉴 5,000원이 담긴 방. 최소주문금액 15,000원을 딱 채운다
    GroupOrder room;
    Participation memberParticipation;
    OrderItem memberItem;

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
        dorm = pickupSpotRepository.save(new PickupSpot(hankuk, "제1기숙사 로비", null));

        userSeq = 0;
        viewer = user("구경꾼");
        host = user("방장");
        member = user("참여자");

        Instant now = Instant.now();
        room = groupOrderRepository.save(new GroupOrder(host, dorm, "○○마라탕", 15_000, inHours(1), 4));
        Participation hostParticipation = participationRepository.save(new Participation(room, host, now));
        orderItemRepository.save(hostParticipation.addItem(host.getId(), "마라탕", null, 10_000, 1, now));

        // 참여자는 엔티티의 인원 칸도 같이 올린다. 서비스의 참여가 하는 일과 같다
        room.addParticipant(member, now);
        room = groupOrderRepository.save(room);
        memberParticipation = participationRepository.save(new Participation(room, member, now));
        memberItem = orderItemRepository.save(memberParticipation.addItem(member.getId(), "꿔바로우", null, 5_000, 1, now));
    }

    User user(String nickname) {
        userSeq++;
        User user = new User(hankuk, "user" + userSeq + "@hankuk.ac.kr", "hashed-password", nickname);
        ReflectionTestUtils.setField(user, "emailVerifiedAt", Instant.now());
        ReflectionTestUtils.setField(user, "bankName", "한국은행");
        ReflectionTestUtils.setField(user, "accountNumber", "110123456789");
        ReflectionTestUtils.setField(user, "accountHolder", "김민수");
        return userRepository.save(user);
    }

    Instant inHours(long hours) {
        return Instant.now().plus(hours, ChronoUnit.HOURS);
    }

    String bearer(User user) {
        return "Bearer " + jwtProvider.createAccessToken(user.getId());
    }

    String key() {
        return "rooms:" + hankuk.getId();
    }

    ResultActions list() throws Exception {
        return mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(viewer)));
    }

    // 목록을 한 번 열어 캐시에 담는다. 이 뒤로는 지우지 않는 한 DB가 바뀌어도 담긴 목록이 나온다
    void warmUp() throws Exception {
        list().andExpect(jsonPath("$.rooms.length()").value(1));
        assertThat(redisTemplate.hasKey(key())).isTrue();
    }

    String itemJson(String menuName, long unitPrice) {
        return """
                {"menuName": "%s", "unitPrice": %d, "quantity": 1}
                """.formatted(menuName, unitPrice);
    }

    @Test
    @DisplayName("방을 만들면 지워진다. 새 방이 바로 보인다")
    void create() throws Exception {
        warmUp();

        String body = """
                {"pickupSpotId": %d, "storeName": "새 방", "minOrderAmount": 15000,
                 "deadlineAt": "%s", "capacity": 4}
                """.formatted(dorm.getId(), inHours(2));
        mockMvc.perform(post("/api/group-orders").header("Authorization", bearer(user("새방장")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        assertThat(redisTemplate.hasKey(key())).isFalse();
        list().andExpect(jsonPath("$.rooms.length()").value(2));
    }

    @Test
    @DisplayName("참여하면 지워진다. 인원이 바로 바뀐다")
    void join() throws Exception {
        warmUp();

        mockMvc.perform(post("/api/group-orders/{id}/participations", room.getId())
                        .header("Authorization", bearer(user("새참여자"))))
                .andExpect(status().isCreated());

        assertThat(redisTemplate.hasKey(key())).isFalse();
        list().andExpect(jsonPath("$.rooms[0].participantCount").value(3));
    }

    @Test
    @DisplayName("나가면 지워진다. 인원과 메뉴 합계가 바로 바뀐다")
    void leave() throws Exception {
        warmUp();

        mockMvc.perform(delete("/api/participations/{id}", memberParticipation.getId())
                        .header("Authorization", bearer(member)))
                .andExpect(status().is2xxSuccessful());

        assertThat(redisTemplate.hasKey(key())).isFalse();
        list().andExpect(jsonPath("$.rooms[0].participantCount").value(1))
                .andExpect(jsonPath("$.rooms[0].menuTotalAmount").value(10_000));
    }

    @Test
    @DisplayName("메뉴를 담으면 지워진다. 합계가 바로 바뀐다")
    void addItem() throws Exception {
        warmUp();

        mockMvc.perform(post("/api/participations/{id}/order-items", memberParticipation.getId())
                        .header("Authorization", bearer(member))
                        .contentType(MediaType.APPLICATION_JSON).content(itemJson("공기밥", 1_000)))
                .andExpect(status().isCreated());

        assertThat(redisTemplate.hasKey(key())).isFalse();
        list().andExpect(jsonPath("$.rooms[0].menuTotalAmount").value(16_000));
    }

    @Test
    @DisplayName("메뉴를 고치면 지워진다. 합계가 바로 바뀐다")
    void updateItem() throws Exception {
        warmUp();

        mockMvc.perform(put("/api/order-items/{id}", memberItem.getId())
                        .header("Authorization", bearer(member))
                        .contentType(MediaType.APPLICATION_JSON).content(itemJson("꿔바로우 대", 8_000)))
                .andExpect(status().isOk());

        assertThat(redisTemplate.hasKey(key())).isFalse();
        list().andExpect(jsonPath("$.rooms[0].menuTotalAmount").value(18_000));
    }

    @Test
    @DisplayName("메뉴를 빼면 지워진다. 합계가 바로 바뀐다")
    void removeItem() throws Exception {
        warmUp();

        mockMvc.perform(delete("/api/order-items/{id}", memberItem.getId())
                        .header("Authorization", bearer(member)))
                .andExpect(status().is2xxSuccessful());

        assertThat(redisTemplate.hasKey(key())).isFalse();
        list().andExpect(jsonPath("$.rooms[0].menuTotalAmount").value(10_000));
    }

    @Test
    @DisplayName("방장이 마감하면 지워진다. 방이 바로 빠진다")
    void close() throws Exception {
        warmUp();

        mockMvc.perform(post("/api/group-orders/{id}/close", room.getId())
                        .header("Authorization", bearer(host)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        assertThat(redisTemplate.hasKey(key())).isFalse();
        list().andExpect(jsonPath("$.rooms.length()").value(0));
    }

    @Test
    @DisplayName("방장이 취소하면 지워진다. 방이 바로 빠진다")
    void cancel() throws Exception {
        warmUp();

        mockMvc.perform(post("/api/group-orders/{id}/cancel", room.getId())
                        .header("Authorization", bearer(host))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        assertThat(redisTemplate.hasKey(key())).isFalse();
        list().andExpect(jsonPath("$.rooms.length()").value(0));
    }

    @Test
    @DisplayName("스케줄러가 마감 시각이 지난 방을 넘기면 지워진다")
    void expire() throws Exception {
        warmUp();

        // 마감 시각(1시간 뒤)이 지난 시각으로 스케줄러를 부른다. 지금 목록에서는 아직 모집중으로 보이는 방이다
        scheduler.runAt(inHours(2));

        assertThat(groupOrderRepository.findById(room.getId()).orElseThrow().getStatus())
                .isEqualTo(GroupOrderStatus.CLOSED);
        assertThat(redisTemplate.hasKey(key())).isFalse();
        list().andExpect(jsonPath("$.rooms.length()").value(0));
    }

    @Test
    @DisplayName("거절된 요청은 아무것도 안 바꾸므로 목록도 그대로다")
    void rejectedRequestKeepsList() throws Exception {
        warmUp();

        // 이미 참여한 사람이 또 참여한다. 409로 거절된다
        mockMvc.perform(post("/api/group-orders/{id}/participations", room.getId())
                        .header("Authorization", bearer(member)))
                .andExpect(status().isConflict());

        list().andExpect(jsonPath("$.rooms[0].participantCount").value(2));
    }
}
