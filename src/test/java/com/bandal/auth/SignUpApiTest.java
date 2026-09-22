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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 가입을 HTTP로 부른다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
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

    @Test
    @DisplayName("학교 이메일로 가입하면 201이고 소속 학교가 정해진다")
    void signsUp() throws Exception {
        SignUpRequest request = new SignUpRequest("lee@hankuk.ac.kr", "password1234", "마라탕러버");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.email").value("lee@hankuk.ac.kr"))
                .andExpect(jsonPath("$.nickname").value("마라탕러버"))
                .andExpect(jsonPath("$.universityId").value(hankuk.getId()))
                .andExpect(jsonPath("$.universityName").value("한국대학교"))
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andExpect(jsonPath("$.trustScore").value(50));
    }

    @Test
    @DisplayName("비밀번호는 해시로 저장되고 응답에 나가지 않는다")
    void storesHashedPassword() throws Exception {
        SignUpRequest request = new SignUpRequest("lee@hankuk.ac.kr", "password1234", "마라탕러버");

        String body = mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("password1234");

        User saved = userRepository.findByEmail("lee@hankuk.ac.kr").orElseThrow();
        assertThat(saved.getPassword()).isNotEqualTo("password1234");
        // 해시만 보고 원문을 알 수는 없지만, 맞는지 대조는 할 수 있다
        assertThat(passwordEncoder.matches("password1234", saved.getPassword())).isTrue();
        assertThat(saved.getEmailVerifiedAt()).isNull();
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
    @DisplayName("모르는 학교 도메인이면 400이다")
    void rejectsUnknownDomain() throws Exception {
        SignUpRequest request = new SignUpRequest("park@minguk.ac.kr", "password1234", "외부인");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("도메인 대소문자가 달라도 같은 학교로 본다")
    void ignoresDomainCase() throws Exception {
        SignUpRequest request = new SignUpRequest("lee@HANKUK.AC.KR", "password1234", "마라탕러버");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.universityId").value(hankuk.getId()));
    }

    @Test
    @DisplayName("이미 가입된 이메일이면 409다")
    void rejectsDuplicateEmail() throws Exception {
        SignUpRequest request = new SignUpRequest("lee@hankuk.ac.kr", "password1234", "마라탕러버");
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(json(request)))
                .andExpect(status().isCreated());

        SignUpRequest again = new SignUpRequest("lee@hankuk.ac.kr", "password1234", "다른닉네임");
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(json(again)))
                .andExpect(status().isConflict());

        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 쓰는 닉네임이면 409다")
    void rejectsDuplicateNickname() throws Exception {
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SignUpRequest("lee@hankuk.ac.kr", "password1234", "마라탕러버"))))
                .andExpect(status().isCreated());

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
