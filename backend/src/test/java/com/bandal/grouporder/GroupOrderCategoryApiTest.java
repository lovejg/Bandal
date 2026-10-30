package com.bandal.grouporder;

import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.JwtProvider;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpot;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.settlement.SettlementRepository;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 음식 카테고리: 방을 만들 때 1~3개 고르고, 목록을 카테고리로 거른다(하나라도 맞으면)
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GroupOrderCategoryApiTest {

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

    PickupSpot pickupSpot;
    User host;
    User viewer;

    @BeforeEach
    void setUp() {
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
        viewer = saveUser(hankuk, "lee@hankuk.ac.kr", "마라탕러버");
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

    // categoriesJson: ["CHICKEN"] 같은 JSON 배열 글자. 빠진 경우를 보려고 null이면 칸을 아예 넣지 않는다
    ResultActions createRoom(String storeName, String categoriesJson) throws Exception {
        String categories = categoriesJson == null ? "" : ", \"categories\": " + categoriesJson;
        return mockMvc.perform(post("/api/group-orders")
                .header("Authorization", bearer(host))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"pickupSpotId": %d, "storeName": "%s", "minOrderAmount": 15000,
                         "deadlineAt": "%s", "capacity": 4%s}
                        """.formatted(pickupSpot.getId(), storeName, Instant.now().plus(2, ChronoUnit.HOURS), categories)));
    }

    List<String> storesInList(String query) throws Exception {
        String body = mockMvc.perform(get("/api/group-orders" + query).header("Authorization", bearer(viewer)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.rooms[*].storeName");
    }

    @Test
    @DisplayName("고른 카테고리가 방 응답에 나오고, 방 상세에도 나온다")
    void createsWithCategories() throws Exception {
        String body = createRoom("○○치킨", "[\"CHICKEN\", \"LATE_NIGHT\"]")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categories", containsInAnyOrder("CHICKEN", "LATE_NIGHT")))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(body, "$.id");

        mockMvc.perform(get("/api/group-orders/" + id).header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.categories", containsInAnyOrder("CHICKEN", "LATE_NIGHT")));
    }

    @Test
    @DisplayName("카테고리를 안 고르면 400")
    void requiresCategory() throws Exception {
        createRoom("○○치킨", null).andExpect(status().isBadRequest());
        createRoom("○○치킨", "[]").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("4개 이상 고르면 400")
    void atMostThree() throws Exception {
        createRoom("○○치킨", "[\"CHICKEN\", \"PIZZA\", \"LATE_NIGHT\", \"KOREAN\"]").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("없는 카테고리 이름이면 400")
    void unknownCategory() throws Exception {
        createRoom("○○치킨", "[\"SUSHI_BAR\"]").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("목록을 카테고리로 거르면 하나라도 맞는 방만 나오고, 두 개가 맞아도 한 번만 나온다")
    void filtersByAnyMatch() throws Exception {
        createRoom("○○치킨", "[\"CHICKEN\"]").andExpect(status().isCreated());
        createRoom("△△피자", "[\"PIZZA\", \"LATE_NIGHT\"]").andExpect(status().isCreated());
        createRoom("□□국밥", "[\"KOREAN\"]").andExpect(status().isCreated());

        assertThat(storesInList("?categories=PIZZA")).containsExactly("△△피자");
        assertThat(storesInList("?categories=CHICKEN,LATE_NIGHT")).containsExactlyInAnyOrder("○○치킨", "△△피자");
        assertThat(storesInList("?categories=PIZZA,LATE_NIGHT")).containsExactly("△△피자");
        assertThat(storesInList("")).containsExactlyInAnyOrder("○○치킨", "△△피자", "□□국밥");
    }

    @Test
    @DisplayName("카테고리를 넣기 전에 만든 방은 거르지 않은 목록에만 나오고, 카드의 카테고리는 빈 목록이다")
    void oldRoomsWithoutCategory() throws Exception {
        groupOrderRepository.save(new GroupOrder(host, pickupSpot, "옛날방", 15000, Instant.now().plus(2, ChronoUnit.HOURS), 4));
        createRoom("○○치킨", "[\"CHICKEN\"]").andExpect(status().isCreated());

        assertThat(storesInList("?categories=CHICKEN")).containsExactly("○○치킨");
        mockMvc.perform(get("/api/group-orders").header("Authorization", bearer(viewer)))
                .andExpect(jsonPath("$.rooms[?(@.storeName == '옛날방')].categories[0]").doesNotExist())
                .andExpect(jsonPath("$.rooms[?(@.storeName == '○○치킨')].categories[0]").value("CHICKEN"));
    }

    @Test
    @DisplayName("거른 목록은 캐시를 타지 않는다: 거르지 않은 첫 페이지가 캐시돼 있어도 거른 결과는 그때그때 DB에서 만든다")
    void filteredListSkipsCache() throws Exception {
        createRoom("○○치킨", "[\"CHICKEN\"]").andExpect(status().isCreated());
        storesInList(""); // 거르지 않은 첫 페이지를 캐시에 올린다

        assertThat(storesInList("?categories=PIZZA")).isEmpty();
        assertThat(storesInList("?categories=CHICKEN")).containsExactly("○○치킨");
    }
}
