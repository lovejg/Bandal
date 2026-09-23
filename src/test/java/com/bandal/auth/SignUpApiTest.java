package com.bandal.auth;

import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.dto.SignUpRequest;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 가입을 HTTP로 부른다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
// 액추에이터의 메일 상태 점검은 진짜 JavaMailSenderImpl을 찾는다. 가짜로 바꿔 끼우면
// 찾을 게 없어서 앱이 아예 못 뜬다. 테스트에서는 이 점검을 끈다
@TestPropertySource(properties = "management.health.mail.enabled=false")
class SignUpApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    UniversityRepository universityRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    OrderItemRepository orderItemRepository;

    @Autowired
    ParticipationRepository participationRepository;

    @Autowired
    GroupOrderRepository groupOrderRepository;

    @Autowired
    PickupSpotRepository pickupSpotRepository;

    // 진짜로 메일을 보내면 테스트가 SMTP 서버에 매달린다. 가짜로 바꿔 끼운다
    @MockitoBean
    JavaMailSender mailSender;

    University hankuk;

    @BeforeEach
    void setUp() {
        orderItemRepository.deleteAll();
        participationRepository.deleteAll();
        groupOrderRepository.deleteAll();
        userRepository.deleteAll();
        pickupSpotRepository.deleteAll();
        universityRepository.deleteAll();

        hankuk = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
    }

    String json(SignUpRequest request) {
        return objectMapper.writeValueAsString(request);
    }

    SimpleMailMessage sentMail() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("학교 이메일로 가입하면 202이고 메일을 보냈다고만 답한다")
    void signsUp() throws Exception {
        SignUpRequest request = new SignUpRequest("lee@hankuk.ac.kr", "password1234", "마라탕러버");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").isNotEmpty());

        User saved = userRepository.findByEmail("lee@hankuk.ac.kr").orElseThrow();
        assertThat(saved.getUniversity().getId()).isEqualTo(hankuk.getId());
        assertThat(saved.getTrustScore()).isEqualTo(50);
        // 가입만으로는 아직 인증이 아니다
        assertThat(saved.getEmailVerifiedAt()).isNull();
    }

    @Test
    @DisplayName("가입 응답에는 사용자 정보가 들어가지 않는다")
    void responseCarriesNoUserInfo() throws Exception {
        SignUpRequest request = new SignUpRequest("lee@hankuk.ac.kr", "password1234", "마라탕러버");

        String body = mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        // id나 닉네임이 있으면 그 존재만으로 새 계정이 생겼는지가 드러난다 (ADR-028)
        assertThat(body).doesNotContain("마라탕러버");
        assertThat(body).doesNotContain("lee@hankuk.ac.kr");
        assertThat(body).doesNotContain("password1234");
    }

    @Test
    @DisplayName("가입하면 인증 메일이 그 주소로 나간다")
    void sendsVerificationMail() throws Exception {
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SignUpRequest("lee@hankuk.ac.kr", "password1234", "마라탕러버"))))
                .andExpect(status().isAccepted());

        SimpleMailMessage mail = sentMail();
        assertThat(mail.getTo()).containsExactly("lee@hankuk.ac.kr");
        assertThat(mail.getText()).contains("token=");
    }

    @Test
    @DisplayName("메일 서버가 죽어 있어도 가입은 성공한다")
    void survivesMailFailure() throws Exception {
        // 메일 발송이 가입 트랜잭션 안에 있으면 여기서 롤백이 나서 계정이 사라진다 (ADR-027)
        doThrow(new MailSendException("메일 서버가 죽었다"))
                .when(mailSender).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));

        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SignUpRequest("lee@hankuk.ac.kr", "password1234", "마라탕러버"))))
                .andExpect(status().isAccepted());

        // 계정은 남아야 한다. 메일은 재전송으로 다시 받을 수 있다
        assertThat(userRepository.findByEmail("lee@hankuk.ac.kr")).isPresent();
    }

    @Test
    @DisplayName("비밀번호는 해시로 저장되고 응답에 나가지 않는다")
    void storesHashedPassword() throws Exception {
        SignUpRequest request = new SignUpRequest("lee@hankuk.ac.kr", "password1234", "마라탕러버");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isAccepted());

        User saved = userRepository.findByEmail("lee@hankuk.ac.kr").orElseThrow();
        assertThat(saved.getPassword()).isNotEqualTo("password1234");
        // 해시만 보고 원문을 알 수는 없지만, 맞는지 대조는 할 수 있다
        assertThat(passwordEncoder.matches("password1234", saved.getPassword())).isTrue();
    }

    @Test
    @DisplayName("같은 비밀번호로 둘이 가입해도 해시는 서로 다르다")
    void hashesDifferPerUser() throws Exception {
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(json(new SignUpRequest("lee@hankuk.ac.kr", "password1234", "마라탕러버"))));
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(json(new SignUpRequest("kim@hankuk.ac.kr", "password1234", "배고파"))));

        String first = userRepository.findByEmail("lee@hankuk.ac.kr").orElseThrow().getPassword();
        String second = userRepository.findByEmail("kim@hankuk.ac.kr").orElseThrow().getPassword();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("모르는 학교 도메인이면 400이고 메일도 안 나간다")
    void rejectsUnknownDomain() throws Exception {
        SignUpRequest request = new SignUpRequest("park@minguk.ac.kr", "password1234", "외부인");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        assertThat(userRepository.count()).isZero();
        verify(mailSender, never()).send((SimpleMailMessage) org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("도메인 대소문자가 달라도 같은 학교로 본다")
    void ignoresDomainCase() throws Exception {
        SignUpRequest request = new SignUpRequest("lee@HANKUK.AC.KR", "password1234", "마라탕러버");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isAccepted());

        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 가입된 이메일이어도 가입 성공과 똑같이 답한다")
    void hidesDuplicateEmail() throws Exception {
        String first = mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SignUpRequest("lee@hankuk.ac.kr", "password1234", "마라탕러버"))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SignUpRequest("lee@hankuk.ac.kr", "another-password", "다른닉네임"))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        // 한 글자라도 다르면 계정 존재 여부가 샌다 (ADR-028)
        assertThat(second).isEqualTo(first);
        // 계정이 늘지도, 비밀번호가 덮이지도 않는다
        assertThat(userRepository.count()).isEqualTo(1);
        User saved = userRepository.findByEmail("lee@hankuk.ac.kr").orElseThrow();
        assertThat(passwordEncoder.matches("password1234", saved.getPassword())).isTrue();
        assertThat(saved.getNickname()).isEqualTo("마라탕러버");
    }

    @Test
    @DisplayName("이미 가입된 이메일이면 주인에게만 알리는 메일이 간다")
    void tellsTheOwnerInstead() throws Exception {
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(json(new SignUpRequest("lee@hankuk.ac.kr", "password1234", "마라탕러버"))));

        org.mockito.Mockito.clearInvocations(mailSender);

        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SignUpRequest("lee@hankuk.ac.kr", "password1234", "다른닉네임"))))
                .andExpect(status().isAccepted());

        SimpleMailMessage mail = sentMail();
        assertThat(mail.getTo()).containsExactly("lee@hankuk.ac.kr");
        // 새 인증 링크가 아니라 "이미 계정이 있다"는 안내여야 한다
        assertThat(mail.getText()).doesNotContain("token=");
    }

    @Test
    @DisplayName("이미 쓰는 닉네임이면 409다")
    void rejectsDuplicateNickname() throws Exception {
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SignUpRequest("lee@hankuk.ac.kr", "password1234", "마라탕러버"))))
                .andExpect(status().isAccepted());

        // 닉네임은 방 목록에 그대로 보이는 공개 정보라 감추지 않는다 (ADR-028)
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SignUpRequest("kim@hankuk.ac.kr", "password1234", "마라탕러버"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("이메일 형식이 아니면 400이다")
    void rejectsMalformedEmail() throws Exception {
        SignUpRequest request = new SignUpRequest("hankuk.ac.kr", "password1234", "마라탕러버");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("email")));
    }

    @Test
    @DisplayName("비밀번호가 8자보다 짧으면 400이다")
    void rejectsShortPassword() throws Exception {
        SignUpRequest request = new SignUpRequest("lee@hankuk.ac.kr", "1234", "마라탕러버");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("password")));
    }

    @Test
    @DisplayName("닉네임이 비면 400이다")
    void rejectsBlankNickname() throws Exception {
        SignUpRequest request = new SignUpRequest("lee@hankuk.ac.kr", "password1234", "  ");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest());
    }
}
