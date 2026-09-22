package com.bandal.auth;

import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.dto.LoginRequest;
import com.bandal.auth.dto.RefreshRequest;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpot;
import com.bandal.pickupspot.PickupSpotRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 로그인, 토큰 갱신, 로그아웃.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoginApiTest {

    static final String PASSWORD = "password1234";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    UniversityRepository universityRepository;

    @Autowired
    PickupSpotRepository pickupSpotRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    OrderItemRepository orderItemRepository;

    @Autowired
    ParticipationRepository participationRepository;

    @Autowired
    GroupOrderRepository groupOrderRepository;

    User member;
    PickupSpot pickupSpot;

    @BeforeEach
    void setUp() {
        orderItemRepository.deleteAll();
        participationRepository.deleteAll();
        groupOrderRepository.deleteAll();
        userRepository.deleteAll();
        pickupSpotRepository.deleteAll();
        universityRepository.deleteAll();

        University hankuk = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        pickupSpot = pickupSpotRepository.save(new PickupSpot(hankuk, "제1기숙사 로비", null));
        member = userRepository.save(new User(
                hankuk, "lee@hankuk.ac.kr", passwordEncoder.encode(PASSWORD), "마라탕러버"));
    }

    String login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    String field(String json, String name) {
        return objectMapper.readTree(json).get(name).asString();
    }

    @Test
    @DisplayName("맞는 비밀번호로 로그인하면 토큰 두 개가 나온다")
    void logsIn() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("lee@hankuk.ac.kr", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    @DisplayName("액세스 토큰에는 사용자 id가 들어 있다")
    void accessTokenCarriesUserId() throws Exception {
        String accessToken = field(login("lee@hankuk.ac.kr", PASSWORD), "accessToken");

        assertThat(jwtProvider.parseUserId(accessToken)).isEqualTo(member.getId());
    }

    @Test
    @DisplayName("받은 토큰으로 방을 만들 수 있다")
    void tokenWorksOnRealApi() throws Exception {
        String accessToken = field(login("lee@hankuk.ac.kr", PASSWORD), "accessToken");

        String body = """
                {"pickupSpotId": %d, "storeName": "○○마라탕", "minOrderAmount": 15000,
                 "deadlineAt": "%s", "capacity": 4}
                """.formatted(pickupSpot.getId(), Instant.now().plus(2, ChronoUnit.HOURS));

        mockMvc.perform(post("/api/group-orders")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hostId").value(member.getId()));
    }

    @Test
    @DisplayName("비밀번호가 틀리면 401이다")
    void rejectsWrongPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("lee@hankuk.ac.kr", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("없는 이메일과 틀린 비밀번호의 응답이 똑같다")
    void doesNotRevealWhetherAccountExists() throws Exception {
        String unknownEmail = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("nobody@hankuk.ac.kr", PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String wrongPassword = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("lee@hankuk.ac.kr", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // 메시지가 같아야 한다. 다르면 어느 이메일이 가입돼 있는지 알아낼 수 있다 (ADR-026)
        assertThat(unknownEmail).isEqualTo(wrongPassword);
    }

    @Test
    @DisplayName("리프레시 토큰으로 새 액세스 토큰을 받는다")
    void refreshesAccessToken() throws Exception {
        String refreshToken = field(login("lee@hankuk.ac.kr", PASSWORD), "refreshToken");

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("갱신으로 받은 토큰도 같은 사람의 것이다")
    void refreshedTokenBelongsToSameUser() throws Exception {
        String refreshToken = field(login("lee@hankuk.ac.kr", PASSWORD), "refreshToken");

        String body = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(jwtProvider.parseUserId(field(body, "accessToken"))).isEqualTo(member.getId());
    }

    @Test
    @DisplayName("지어낸 리프레시 토큰이면 401이다")
    void rejectsUnknownRefreshToken() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest("made-up-token"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그아웃하면 그 리프레시 토큰으로는 더 갱신할 수 없다")
    void logoutRevokesRefreshToken() throws Exception {
        String refreshToken = field(login("lee@hankuk.ac.kr", PASSWORD), "refreshToken");

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("갱신한 뒤 로그아웃하면 갱신 전 토큰으로도 더 갱신할 수 없다")
    void logoutRevokesEarlierRefreshTokens() throws Exception {
        String first = field(login("lee@hankuk.ac.kr", PASSWORD), "refreshToken");

        String refreshed = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(first))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = field(refreshed, "refreshToken");

        // 클라이언트가 들고 있는 건 새 토큰이다. 이걸로 로그아웃한다
        mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(second))))
                .andExpect(status().isNoContent());

        // 옛 토큰이 살아 있으면 로그아웃이 로그아웃이 아니다
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(first))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("이미 로그아웃한 토큰으로 또 로그아웃해도 204다")
    void logoutIsIdempotent() throws Exception {
        String refreshToken = field(login("lee@hankuk.ac.kr", PASSWORD), "refreshToken");

        mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("두 번 로그인하면 리프레시 토큰이 서로 다르다")
    void issuesDifferentRefreshTokens() throws Exception {
        String first = field(login("lee@hankuk.ac.kr", PASSWORD), "refreshToken");
        String second = field(login("lee@hankuk.ac.kr", PASSWORD), "refreshToken");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("로그아웃해도 이미 받은 액세스 토큰은 만료까지 살아 있다")
    void accessTokenSurvivesLogout() throws Exception {
        String body = login("lee@hankuk.ac.kr", PASSWORD);
        String accessToken = field(body, "accessToken");

        mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RefreshRequest(field(body, "refreshToken")))));

        // 우리가 택한 구조의 한계다. 즉시 막으려면 매 요청마다 저장소를 봐야 한다 (ADR-024)
        assertThat(jwtProvider.parseUserId(accessToken)).isEqualTo(member.getId());
    }
}
