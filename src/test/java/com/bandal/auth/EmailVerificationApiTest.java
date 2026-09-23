package com.bandal.auth;

import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.dto.LoginRequest;
import com.bandal.auth.dto.SignUpRequest;
import com.bandal.grouporder.GroupOrder;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 이메일 인증. 가입 -> 메일 -> 링크 클릭 -> 쓰기 가능까지 (ADR-027)
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
// 액추에이터의 메일 상태 점검은 진짜 JavaMailSenderImpl을 찾는다. 아래에서 가짜로 바꿔 끼우면
// 찾을 게 없어서 앱이 아예 못 뜬다. 테스트에서는 이 점검을 끈다
@TestPropertySource(properties = "management.health.mail.enabled=false")
class EmailVerificationApiTest {

    static final String PASSWORD = "password1234";
    static final String EMAIL = "lee@hankuk.ac.kr";
    // 링크에서 토큰만 꺼낸다
    static final Pattern TOKEN = Pattern.compile("token=([\\w-]+)");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    StringRedisTemplate redisTemplate;

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

    @MockitoBean
    JavaMailSender mailSender;

    PickupSpot pickupSpot;
    // 이미 인증을 마친 다른 사용자. 미인증 사용자가 구경할 방이 있어야 한다
    User host;

    @BeforeEach
    void setUp() {
        orderItemRepository.deleteAll();
        participationRepository.deleteAll();
        groupOrderRepository.deleteAll();
        userRepository.deleteAll();
        pickupSpotRepository.deleteAll();
        universityRepository.deleteAll();

        // 지난 테스트가 남긴 토큰과 재전송 횟수를 지운다
        redisTemplate.delete(redisTemplate.keys("verify:*"));
        Mockito.clearInvocations(mailSender);

        University hankuk = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        pickupSpot = pickupSpotRepository.save(new PickupSpot(hankuk, "제1기숙사 로비", null));

        User verified = new User(hankuk, "kim@hankuk.ac.kr", "hashed-password", "배고파");
        ReflectionTestUtils.setField(verified, "emailVerifiedAt", Instant.now());
        host = userRepository.save(verified);
    }

    // 가입하고, 메일로 나간 인증 토큰을 돌려준다
    String signUpAndTakeToken() throws Exception {
        signUp(EMAIL, "마라탕러버");
        return tokenFrom(lastMail());
    }

    void signUp(String email, String nickname) throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignUpRequest(email, PASSWORD, nickname))))
                .andExpect(status().isAccepted());
    }

    SimpleMailMessage lastMail() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        Mockito.verify(mailSender, Mockito.atLeastOnce()).send(captor.capture());
        List<SimpleMailMessage> sent = captor.getAllValues();
        return sent.get(sent.size() - 1);
    }

    String tokenFrom(SimpleMailMessage mail) {
        Matcher matcher = TOKEN.matcher(mail.getText());
        assertThat(matcher.find()).as("메일 본문에 인증 링크가 있어야 한다").isTrue();
        return matcher.group(1);
    }

    String accessToken() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(EMAIL, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asString();
    }

    String createRoomBody() {
        return """
                {"pickupSpotId": %d, "storeName": "○○마라탕", "minOrderAmount": 15000,
                 "deadlineAt": "%s", "capacity": 4}
                """.formatted(pickupSpot.getId(), Instant.now().plus(2, ChronoUnit.HOURS));
    }

    @Nested
    @DisplayName("미인증 사용자")
    class Unverified {

        @Test
        @DisplayName("가입 직후에는 방을 만들 수 없다")
        void cannotWrite() throws Exception {
            signUp(EMAIL, "마라탕러버");

            mockMvc.perform(post("/api/group-orders")
                            .header("Authorization", "Bearer " + accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createRoomBody()))
                    // 누군지는 안다. 자격이 모자랄 뿐이라 401이 아니라 403이다
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.message").value("이메일 인증이 필요합니다"));

            assertThat(groupOrderRepository.count()).isZero();
        }

        @Test
        @DisplayName("로그인은 된다")
        void canLogIn() throws Exception {
            signUp(EMAIL, "마라탕러버");

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new LoginRequest(EMAIL, PASSWORD))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("남의 방 조회는 된다. 앱 구경까지는 허용한다")
        void canRead() throws Exception {
            signUp(EMAIL, "마라탕러버");
            GroupOrder room = groupOrderRepository.save(new GroupOrder(
                    host, pickupSpot, "○○마라탕", 15_000, Instant.now().plus(2, ChronoUnit.HOURS), 4));

            mockMvc.perform(get("/api/group-orders/" + room.getId())
                            .header("Authorization", "Bearer " + accessToken()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("내 정보에 emailVerified가 false로 나온다")
        void meShowsUnverified() throws Exception {
            signUp(EMAIL, "마라탕러버");

            mockMvc.perform(get("/api/auth/me")
                            .header("Authorization", "Bearer " + accessToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.emailVerified").value(false));
        }
    }

    @Nested
    @DisplayName("인증 링크")
    class Verify {

        @Test
        @DisplayName("메일 속 링크를 누르면 인증이 끝난다")
        void verifies() throws Exception {
            String token = signUpAndTakeToken();

            mockMvc.perform(get("/api/auth/verify").param("token", token))
                    .andExpect(status().isOk());

            User user = userRepository.findByEmail(EMAIL).orElseThrow();
            assertThat(user.getEmailVerifiedAt()).isNotNull();
        }

        @Test
        @DisplayName("인증하면 방을 만들 수 있다")
        void unlocksWriting() throws Exception {
            String token = signUpAndTakeToken();
            mockMvc.perform(get("/api/auth/verify").param("token", token))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/group-orders")
                            .header("Authorization", "Bearer " + accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createRoomBody()))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("인증 전에 받은 토큰이라도 인증 뒤에는 바로 통한다")
        void oldAccessTokenWorksRightAfterVerifying() throws Exception {
            String token = signUpAndTakeToken();
            // 인증하기 전에 받아둔 액세스 토큰
            String accessToken = accessToken();

            mockMvc.perform(get("/api/auth/verify").param("token", token))
                    .andExpect(status().isOk());

            // 토큰에 인증 여부를 담았다면 여기서 403이 난다. 매 요청 DB를 읽는 이유다 (ADR-027)
            mockMvc.perform(post("/api/group-orders")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createRoomBody()))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("같은 링크를 두 번 누르면 400이다")
        void rejectsReuse() throws Exception {
            String token = signUpAndTakeToken();
            mockMvc.perform(get("/api/auth/verify").param("token", token))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/auth/verify").param("token", token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("지어낸 토큰, 이미 쓴 토큰, 만료된 토큰은 응답이 모두 같다")
        void failuresLookTheSame() throws Exception {
            String used = signUpAndTakeToken();
            mockMvc.perform(get("/api/auth/verify").param("token", used));

            signUp("park@hankuk.ac.kr", "꿔바로우");
            String expired = tokenFrom(lastMail());
            // TTL이 지난 상황을 흉내낸다. Redis에서 키가 사라지는 것과 같다
            redisTemplate.delete("verify:" + expired);

            String usedBody = verifyFailure(used);
            String expiredBody = verifyFailure(expired);
            String madeUp = verifyFailure("이건-그냥-지어낸-값");

            // 구분해서 알려주면 "이건 실재했던 토큰이구나"를 알려주는 셈이다 (ADR-027)
            assertThat(expiredBody).isEqualTo(usedBody);
            assertThat(madeUp).isEqualTo(usedBody);
            // 셋이 같기만 해서는 부족하다. 사용자가 읽을 수 있는 문장이어야 한다
            assertThat(usedBody).contains("만료되었거나 이미 사용된 링크입니다");
        }

        String verifyFailure(String token) throws Exception {
            return mockMvc.perform(get("/api/auth/verify").param("token", token))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
        }
    }

    @Nested
    @DisplayName("재전송")
    class Resend {

        @Test
        @DisplayName("다시 보내면 새 링크가 온다")
        void sendsAgain() throws Exception {
            String first = signUpAndTakeToken();

            mockMvc.perform(post("/api/auth/verify/resend")
                            .header("Authorization", "Bearer " + accessToken()))
                    .andExpect(status().isNoContent());

            String second = tokenFrom(lastMail());
            assertThat(second).isNotEqualTo(first);

            mockMvc.perform(get("/api/auth/verify").param("token", second))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("다시 보내도 먼저 받은 링크는 그대로 쓸 수 있다")
        void keepsOlderLinkAlive() throws Exception {
            String first = signUpAndTakeToken();

            mockMvc.perform(post("/api/auth/verify/resend")
                            .header("Authorization", "Bearer " + accessToken()))
                    .andExpect(status().isNoContent());

            // 옛 링크를 끄지 않는다. 어차피 같은 메일함 안에 있고, 끄면 "왜 이 링크는 안 되지"가 된다
            mockMvc.perform(get("/api/auth/verify").param("token", first))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("로그인하지 않으면 401이다")
        void requiresLogin() throws Exception {
            signUp(EMAIL, "마라탕러버");

            // 이게 열려 있으면 누구나 남의 주소로 메일을 계속 보낼 수 있다
            mockMvc.perform(post("/api/auth/verify/resend"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("창 안에서 5번까지 되고 6번째는 429다")
        void limitsResend() throws Exception {
            signUp(EMAIL, "마라탕러버");
            String accessToken = accessToken();

            for (int i = 0; i < 5; i++) {
                mockMvc.perform(post("/api/auth/verify/resend")
                                .header("Authorization", "Bearer " + accessToken))
                        .andExpect(status().isNoContent());
            }

            mockMvc.perform(post("/api/auth/verify/resend")
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.status").value(429));
        }

        @Test
        @DisplayName("이미 인증한 사람이 다시 보내면 409다")
        void rejectsWhenAlreadyVerified() throws Exception {
            String token = signUpAndTakeToken();
            mockMvc.perform(get("/api/auth/verify").param("token", token))
                    .andExpect(status().isOk());

            // 본인 정보라 감출 게 없다. 여긴 뭉뚱그리지 않아도 된다
            mockMvc.perform(post("/api/auth/verify/resend")
                            .header("Authorization", "Bearer " + accessToken()))
                    .andExpect(status().isConflict());
        }
    }
}
