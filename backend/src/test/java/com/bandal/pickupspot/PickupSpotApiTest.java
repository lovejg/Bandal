package com.bandal.pickupspot;

import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.JwtProvider;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.grouporder.dto.CreateGroupOrderRequest;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.dto.CreatePickupSpotRequest;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 거점 목록과 거점 만들기. 방을 만들 때 수령 거점을 고르는 화면이다 (ADR-033, 036)
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PickupSpotApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JwtProvider jwtProvider;

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
    // 한국대 민수와 지수, 민국대 영희
    User minsu;
    User jisu;
    User younghee;

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

        userSeq = 0;
        minsu = user(hankuk, "민수", true);
        jisu = user(hankuk, "지수", true);
        younghee = user(minguk, "영희", true);
    }

    // 계좌까지 등록한 사용자. 마지막 테스트에서 만든 거점으로 방을 만들어 보려면 계좌가 있어야 한다 (ADR-031)
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

    String bearer(User user) {
        return "Bearer " + jwtProvider.createAccessToken(user.getId());
    }

    ResultActions list(User viewer) throws Exception {
        return mockMvc.perform(get("/api/pickup-spots").header("Authorization", bearer(viewer)));
    }

    ResultActions create(User creator, String name, String description) throws Exception {
        return mockMvc.perform(post("/api/pickup-spots")
                .header("Authorization", bearer(creator))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreatePickupSpotRequest(name, description))));
    }

    // 목록

    @Test
    @DisplayName("로그인하지 않으면 목록을 볼 수 없다")
    void listRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/pickup-spots"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("내 학교 거점만 이름 가나다순으로 나온다")
    void listsMyUniversitySortedByName() throws Exception {
        // 일부러 가나다와 다르게 넣는다. DB 기본 정렬(en_US)에 맡기면 가나다 반대로 나온다 (JOURNAL 2026-10-01)
        pickupSpotRepository.save(new PickupSpot(hankuk, "중앙도서관 앞", null));
        pickupSpotRepository.save(new PickupSpot(hankuk, "공대 7호관 앞", null));
        pickupSpotRepository.save(new PickupSpot(minguk, "민국대 정문", null));
        pickupSpotRepository.save(new PickupSpot(hankuk, "제1기숙사 로비", null));

        list(minsu)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spots[*].name").value(contains("공대 7호관 앞", "제1기숙사 로비", "중앙도서관 앞")));
    }

    @Test
    @DisplayName("한 줄에 id, 이름, 설명이 들어 있다")
    void listCarriesFields() throws Exception {
        PickupSpot dorm = pickupSpotRepository.save(new PickupSpot(hankuk, "제1기숙사 로비", "정문 쪽 계단 옆"));

        list(minsu)
                .andExpect(jsonPath("$.spots[0].id").value(dorm.getId()))
                .andExpect(jsonPath("$.spots[0].name").value("제1기숙사 로비"))
                .andExpect(jsonPath("$.spots[0].description").value("정문 쪽 계단 옆"));
    }

    @Test
    @DisplayName("거점이 하나도 없으면 빈 목록이다")
    void emptyList() throws Exception {
        // 서비스 첫날의 모습. 첫 사람이 만들어야 목록이 생긴다 (ADR-033)
        list(minsu)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spots.length()").value(0));
    }

    @Test
    @DisplayName("메일 인증을 안 한 사용자도 목록은 볼 수 있다")
    void unverifiedCanList() throws Exception {
        pickupSpotRepository.save(new PickupSpot(hankuk, "제1기숙사 로비", null));

        list(user(hankuk, "신입생", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spots.length()").value(1));
    }

    // 만들기

    @Test
    @DisplayName("거점을 만들면 201과 만들어진 거점이 돌아온다")
    void createsPickupSpot() throws Exception {
        create(minsu, "공대 7호관 앞", "정문 쪽 벤치")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("공대 7호관 앞"))
                .andExpect(jsonPath("$.description").value("정문 쪽 벤치"));
    }

    @Test
    @DisplayName("설명 없이도 만들 수 있다")
    void createsWithoutDescription() throws Exception {
        create(minsu, "공대 7호관 앞", null)
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("민수가 만든 거점이 같은 학교 지수에게는 보이고, 다른 학교 영희에게는 안 보인다")
    void sharedWithinUniversity() throws Exception {
        create(minsu, "공대 7호관 앞", null).andExpect(status().isCreated());

        list(jisu)
                .andExpect(jsonPath("$.spots[*].name").value(contains("공대 7호관 앞")));
        list(younghee)
                .andExpect(jsonPath("$.spots.length()").value(0));
    }

    @Test
    @DisplayName("메일 인증을 안 한 사용자는 거점을 만들 수 없다")
    void unverifiedCannotCreate() throws Exception {
        // 쓰기는 인증 사용자만 (ADR-027, 034). 거점은 학교 전체가 같이 보는 목록이라 더 그렇다
        create(user(hankuk, "신입생", false), "공대 7호관 앞", null)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("이름이 비면 400이다")
    void rejectsBlankName() throws Exception {
        create(minsu, " ", null)
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("이름이 31자면 400이다")
    void rejectsTooLongName() throws Exception {
        create(minsu, "가".repeat(31), null)
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("이름이 특수기호뿐이면 400이다")
    void rejectsSymbolOnlyName() throws Exception {
        // @NotBlank는 통과한다. 공백이 아니니까. 엔티티 생성자가 막는다
        create(minsu, "!!!", null)
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("같은 학교에 같은 이름이 있으면 409다")
    void rejectsDuplicateName() throws Exception {
        create(minsu, "공대 7호관 앞", null).andExpect(status().isCreated());

        create(jisu, "공대 7호관 앞", null)
                .andExpect(status().isConflict());
        assertThat(pickupSpotRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("띄어쓰기와 기호만 다른 이름도 409다")
    void rejectsNormalizedDuplicate() throws Exception {
        create(minsu, "공대 7호관 앞", null).andExpect(status().isCreated());

        create(jisu, "공대7호관 앞!", null)
                .andExpect(status().isConflict());
        assertThat(pickupSpotRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 학교라면 같은 이름도 만들 수 있다")
    void sameNameInOtherUniversity() throws Exception {
        create(minsu, "공대 7호관 앞", null).andExpect(status().isCreated());

        create(younghee, "공대 7호관 앞", null)
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("만든 거점의 id로 바로 방을 만들 수 있다")
    void createdSpotCanBeUsedForGroupOrder() throws Exception {
        // 시나리오 그대로: 목록에 없어서 만들고, 응답의 id로 곧바로 방을 만든다
        String body = create(minsu, "공대 7호관 앞", null)
                .andReturn().getResponse().getContentAsString();
        JsonNode created = objectMapper.readTree(body);
        long spotId = created.get("id").asLong();

        CreateGroupOrderRequest room = new CreateGroupOrderRequest(
                spotId, "○○마라탕", 15_000L, Instant.now().plus(2, ChronoUnit.HOURS), 4);
        mockMvc.perform(post("/api/group-orders")
                        .header("Authorization", bearer(minsu))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(room)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pickupSpotId").value(spotId));
    }
}
