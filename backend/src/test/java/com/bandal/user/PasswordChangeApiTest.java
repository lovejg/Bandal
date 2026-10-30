package com.bandal.user;

import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.dto.LoginRequest;
import com.bandal.auth.dto.RefreshRequest;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.settlement.SettlementRepository;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 비밀번호 변경. 공용 PC에 로그인해 둔 채로 폰에서 비밀번호를 바꾸는 장면을 기기 두 개(로그인 두 번)로 흉내 낸다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PasswordChangeApiTest {

    static final String EMAIL = "lee@hankuk.ac.kr";
    static final String OLD = "password1234";
    static final String NEW = "newpassword5678";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PasswordEncoder passwordEncoder;

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
        saveVerifiedUser(EMAIL, "마라탕러버");
    }

    // 메일 인증 전 사용자는 쓰기 요청이 막힌다. 여기서 보려는 건 비밀번호라 인증을 마친 상태로 만든다
    void saveVerifiedUser(String email, String nickname) {
        User user = new User(hankuk, email, passwordEncoder.encode(OLD), nickname);
        ReflectionTestUtils.setField(user, "emailVerifiedAt", Instant.now());
        userRepository.save(user);
    }

    // 로그인 한 번 = 기기 하나. 응답 JSON(토큰 두 개)을 그대로 돌려준다
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

    ResultActions changePassword(String accessToken, String current, String next) throws Exception {
        String body = """
                {"currentPassword": "%s", "newPassword": "%s"}
                """.formatted(current, next);
        return mockMvc.perform(patch("/api/users/me/password")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))));
    }

    ResultActions tryLogin(String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(EMAIL, password))));
    }

    @Test
    @DisplayName("현재 비밀번호가 맞으면 바뀐다. 새 비밀번호로는 로그인되고 옛 비밀번호로는 안 된다")
    void changesPassword() throws Exception {
        String phone = login(EMAIL, OLD);

        changePassword(field(phone, "accessToken"), OLD, NEW).andExpect(status().isOk());

        tryLogin(NEW).andExpect(status().isOk());
        tryLogin(OLD).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("바꾸면 다른 기기(공용 PC)의 리프레시 토큰으로는 더 갱신할 수 없다")
    void otherDevicesAreLoggedOut() throws Exception {
        String pc = login(EMAIL, OLD);
        String phone = login(EMAIL, OLD);

        changePassword(field(phone, "accessToken"), OLD, NEW).andExpect(status().isOk());

        refresh(field(pc, "refreshToken")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("바꾼 기기는 응답으로 받은 새 리프레시 토큰으로 계속 갱신할 수 있다")
    void changingDeviceStaysLoggedIn() throws Exception {
        String phone = login(EMAIL, OLD);

        String body = changePassword(field(phone, "accessToken"), OLD, NEW)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        refresh(field(body, "refreshToken")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("바꾼 기기가 들고 있던 옛 리프레시 토큰도 무효다(응답의 새 토큰으로 갈아끼워야 한다)")
    void changingDevicesOldTokenIsRevoked() throws Exception {
        String phone = login(EMAIL, OLD);

        changePassword(field(phone, "accessToken"), OLD, NEW).andExpect(status().isOk());

        refresh(field(phone, "refreshToken")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("현재 비밀번호가 틀리면 400이고, 비밀번호도 다른 기기 로그인도 그대로다")
    void wrongCurrentPassword() throws Exception {
        String pc = login(EMAIL, OLD);
        String phone = login(EMAIL, OLD);

        // 401이면 화면이 로그인이 풀린 줄 안다. 요청한 사람은 로그인한 사람이다
        changePassword(field(phone, "accessToken"), "wrong-password", NEW)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("현재 비밀번호가 맞지 않습니다"));

        tryLogin(OLD).andExpect(status().isOk());
        refresh(field(pc, "refreshToken")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("새 비밀번호가 8자보다 짧으면 400이다(가입과 같은 규칙)")
    void newPasswordTooShort() throws Exception {
        String phone = login(EMAIL, OLD);

        changePassword(field(phone, "accessToken"), OLD, "short1").andExpect(status().isBadRequest());

        tryLogin(OLD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("로그인하지 않고 부르면 401이다")
    void requiresLogin() throws Exception {
        mockMvc.perform(patch("/api/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "%s", "newPassword": "%s"}
                                """.formatted(OLD, NEW)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("내가 비밀번호를 바꿔도 다른 사람의 로그인은 그대로다")
    void otherUsersStayLoggedIn() throws Exception {
        saveVerifiedUser("kim@hankuk.ac.kr", "떡볶이장인");
        String someoneElse = login("kim@hankuk.ac.kr", OLD);
        String phone = login(EMAIL, OLD);

        changePassword(field(phone, "accessToken"), OLD, NEW).andExpect(status().isOk());

        refresh(field(someoneElse, "refreshToken")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("저장되는 건 해시다. 평문이 DB에 들어가지 않는다")
    void storesHash() throws Exception {
        String phone = login(EMAIL, OLD);

        changePassword(field(phone, "accessToken"), OLD, NEW).andExpect(status().isOk());

        String stored = userRepository.findByEmail(EMAIL).orElseThrow().getPassword();
        assertThat(stored).isNotEqualTo(NEW);
        assertThat(passwordEncoder.matches(NEW, stored)).isTrue();
    }
}
