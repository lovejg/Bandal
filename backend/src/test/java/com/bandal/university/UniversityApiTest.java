package com.bandal.university;

import com.bandal.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 가입 화면이 이메일 도메인으로 고를 캠퍼스를 묻는다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "management.health.mail.enabled=false")
class UniversityApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UniversityRepository universityRepository;

    // 다른 테스트가 대학을 지우고 다시 넣으므로, 마이그레이션이 넣은 학교에 기대지 않는다.
    // 테스트마다 처음 보는 도메인을 만들어 쓰면 지우지 않아도 서로 섞이지 않는다
    String domain;

    @BeforeEach
    void setUp() {
        domain = "campus-" + UUID.randomUUID().toString().substring(0, 8) + ".ac.kr";
        universityRepository.save(new University("한국대학교 본캠퍼스", domain));
        universityRepository.save(new University("한국대학교 제2캠퍼스", domain));
    }

    @Test
    @DisplayName("로그인 없이 도메인의 캠퍼스를 등록 순서대로 받는다")
    void listsCampusesWithoutLogin() throws Exception {
        mockMvc.perform(get("/api/universities").param("domain", domain))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.universities.length()").value(2))
                .andExpect(jsonPath("$.universities[0].name").value("한국대학교 본캠퍼스"))
                .andExpect(jsonPath("$.universities[1].name").value("한국대학교 제2캠퍼스"))
                .andExpect(jsonPath("$.universities[0].id").isNumber())
                // 화면에 필요한 건 id와 이름뿐이다
                .andExpect(jsonPath("$.universities[0].emailDomain").doesNotExist());
    }

    @Test
    @DisplayName("대소문자와 앞뒤 공백이 달라도 같은 목록이다")
    void normalizesDomain() throws Exception {
        mockMvc.perform(get("/api/universities").param("domain", " " + domain.toUpperCase() + " "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.universities.length()").value(2));
    }

    @Test
    @DisplayName("모르는 도메인이면 빈 목록이다")
    void unknownDomainIsEmpty() throws Exception {
        mockMvc.perform(get("/api/universities").param("domain", "gmail.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.universities.length()").value(0));
    }

    @Test
    @DisplayName("도메인을 안 주면 400이다")
    void requiresDomain() throws Exception {
        mockMvc.perform(get("/api/universities"))
                .andExpect(status().isBadRequest());
    }
}
