package com.bandal.auth;

import com.bandal.TestMails;
import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.dto.LoginRequest;
import com.bandal.auth.dto.RefreshRequest;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.outbox.OutboxMessageRepository;
import com.bandal.outbox.OutboxRelay;
import com.bandal.outbox.OutboxType;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.ParticipationRepository;
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
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 비밀번호 재설정: 요청 → (집배원) 링크 메일 → 링크의 토큰으로 새 비밀번호
// 스케줄러는 테스트에서 꺼져 있어서 집배원을 손으로 한 바퀴 돌린다(outboxRelay.runAt)
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
// 메일 서버를 가짜로 바꾸면 액추에이터의 메일 상태 점검이 진짜를 찾다 못 떠서 끈다(SignUpApiTest와 같다)
@TestPropertySource(properties = "management.health.mail.enabled=false")
class PasswordResetApiTest {

    static final String EMAIL = "lee@hankuk.ac.kr";
    static final String OLD = "password1234";
    static final String NEW = "newpassword5678";
    static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9-]+)");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    OutboxRelay outboxRelay;

    @Autowired
    StringRedisTemplate redisTemplate;

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

    @Autowired
    OutboxMessageRepository outboxMessageRepository;

    @MockitoBean
    JavaMailSender mailSender;

    User member;

    @BeforeEach
    void setUp() {
        outboxMessageRepository.deleteAll();
        settlementRepository.deleteAll();
        orderItemRepository.deleteAll();
        participationRepository.deleteAll();
        groupOrderRepository.deleteAll();
        userRepository.deleteAll();
        pickupSpotRepository.deleteAll();
        universityRepository.deleteAll();

        University hankuk = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        member = userRepository.save(new User(hankuk, EMAIL, passwordEncoder.encode(OLD), "마라탕러버"));
        ReflectionTestUtils.setField(member, "emailVerifiedAt", Instant.now());
        member = userRepository.save(member);
        // 1분 제한 키가 앞 테스트에서 남아 있으면 요청이 조용히 무시된다
        redisTemplate.delete("reset:requested:" + member.getId());
        TestMails.stub(mailSender);
    }

    ResultActions requestReset(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/password-reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s"}
                        """.formatted(email)));
    }

    ResultActions confirm(String token, String newPassword) throws Exception {
        return mockMvc.perform(post("/api/auth/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token": "%s", "newPassword": "%s"}
                        """.formatted(token, newPassword)));
    }

    ResultActions login(String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(EMAIL, password))));
    }

    // 요청하고 집배원을 돌려 메일을 보낸 뒤, 메일 본문의 링크에서 토큰을 꺼낸다
    String requestAndReadToken() throws Exception {
        requestReset(EMAIL).andExpect(status().isAccepted());
        outboxRelay.runAt(Instant.now());
        TestMails.SentMail mail = TestMails.only(mailSender);
        Matcher matcher = TOKEN.matcher(mail.text());
        assertThat(matcher.find()).as("메일 본문에 링크가 있어야 한다").isTrue();
        return matcher.group(1);
    }

    long resetMailRows() {
        return outboxMessageRepository.findAll().stream()
                .filter(m -> m.getType() == OutboxType.PASSWORD_RESET_MAIL).count();
    }

    @Test
    @DisplayName("가입된 주소와 없는 주소의 응답이 똑같다(누가 가입했는지 새지 않는다)")
    void sameResponseEitherWay() throws Exception {
        String known = requestReset(EMAIL).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String unknown = requestReset("nobody@hankuk.ac.kr").andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(known).isEqualTo(unknown);
        // 메일 줄은 가입된 주소에만 생긴다
        assertThat(resetMailRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("메일에는 화면(/reset-password)으로 가는 링크가 있다")
    void mailHasLinkToResetPage() throws Exception {
        requestReset(EMAIL).andExpect(status().isAccepted());
        outboxRelay.runAt(Instant.now());

        TestMails.SentMail mail = TestMails.only(mailSender);
        assertThat(mail.to()).containsExactly(EMAIL);
        assertThat(mail.subject()).contains("비밀번호 재설정");
        assertThat(mail.text()).contains("/reset-password?token=");
        assertThat(mail.html()).contains("/reset-password?token=");
    }

    @Test
    @DisplayName("링크의 토큰으로 바꾸면 새 비밀번호로만 로그인된다")
    void resetsPassword() throws Exception {
        String token = requestAndReadToken();

        confirm(token, NEW).andExpect(status().isNoContent());

        login(NEW).andExpect(status().isOk());
        login(OLD).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("바꾸면 그 전에 로그인한 모든 기기가 로그아웃된다")
    void logsOutEverywhere() throws Exception {
        String before = login(OLD).andReturn().getResponse().getContentAsString();
        String token = requestAndReadToken();

        confirm(token, NEW).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RefreshRequest(objectMapper.readTree(before).get("refreshToken").asString()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("링크는 한 번만 쓸 수 있다")
    void tokenIsSingleUse() throws Exception {
        String token = requestAndReadToken();
        confirm(token, NEW).andExpect(status().isNoContent());

        confirm(token, "another-password9")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("만료되었거나 이미 사용된 링크입니다"));
        login(NEW).andExpect(status().isOk());
    }

    @Test
    @DisplayName("지어낸 토큰이면 400이다")
    void unknownToken() throws Exception {
        confirm("made-up-token", NEW).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("새 비밀번호가 8자보다 짧으면 400이고 토큰은 그대로 남는다")
    void tooShortKeepsToken() throws Exception {
        String token = requestAndReadToken();

        // 입력 검사에서 걸리면 서비스까지 가지 않아 토큰을 쓰지 않는다. 다시 입력하면 된다
        confirm(token, "short1").andExpect(status().isBadRequest());

        confirm(token, NEW).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("1분 안에 다시 요청하면 응답은 같지만 메일 줄은 하나뿐이다")
    void oncePerMinute() throws Exception {
        requestReset(EMAIL).andExpect(status().isAccepted());
        requestReset(EMAIL).andExpect(status().isAccepted());

        assertThat(resetMailRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("메일 인증 전 계정도 재설정할 수 있고, 그 순간 인증도 끝난다(메일함의 주인임을 보였다)")
    void verifiesEmailToo() throws Exception {
        ReflectionTestUtils.setField(member, "emailVerifiedAt", null);
        userRepository.save(member);
        String token = requestAndReadToken();

        confirm(token, NEW).andExpect(status().isNoContent());

        assertThat(userRepository.findById(member.getId()).orElseThrow().isEmailVerified()).isTrue();
    }

    @Test
    @DisplayName("이메일 대소문자가 가입 때와 달라도 메일이 간다")
    void ignoresEmailCase() throws Exception {
        requestReset("Lee@Hankuk.ac.kr").andExpect(status().isAccepted());

        assertThat(resetMailRows()).isEqualTo(1);
    }
}
