package com.bandal.common;

import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.JwtProvider;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 요청 로그 한 줄과 요청 id. 운영과 같은 JSON 로그(logstash 모양)로 켜고, 찍힌 줄을 직접 읽어 본다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "logging.structured.format.console=logstash")
@ExtendWith(OutputCaptureExtension.class)
class RequestLogFilterTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    UniversityRepository universityRepository;

    @Autowired
    UserRepository userRepository;

    // 이 요청 id가 들어간 JSON 줄. 다른 테스트의 로그와 섞여도 이 id로 골라낸다
    String logLineOf(CapturedOutput output, String requestId) {
        return Arrays.stream(output.getOut().split("\n"))
                .filter(line -> line.startsWith("{") && line.contains("\"requestId\":\"" + requestId + "\"")
                        && line.contains("\"uri\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("요청 로그 줄이 없다. requestId=" + requestId));
    }

    @Test
    @DisplayName("요청마다 요청 id를 만들어 응답 헤더로 주고, 같은 id로 요청 한 줄을 JSON으로 남긴다")
    void logsOneJsonLinePerRequest(CapturedOutput output) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/universities").param("domain", "nowhere.ac.kr"))
                .andExpect(status().isOk())
                .andReturn();

        String requestId = result.getResponse().getHeader(RequestLogFilter.HEADER);
        assertThat(requestId).as("응답의 요청 id").matches("[0-9a-f]{32}");

        String line = logLineOf(output, requestId);
        assertThat(line).contains("\"method\":\"GET\"", "\"uri\":\"/api/universities\"", "\"status\":\"200\"",
                "\"durationMs\":\"", "\"level\":\"INFO\"");
    }

    @Test
    @DisplayName("바깥 Nginx가 붙인 요청 id는 이어받고, 모양이 이상한 값은 버리고 새로 만든다")
    void reusesOnlyWellFormedIncomingId(CapturedOutput output) throws Exception {
        String fromNginx = "0123456789abcdef0123456789abcdef";
        MvcResult kept = mockMvc.perform(get("/api/universities").param("domain", "nowhere.ac.kr")
                        .header(RequestLogFilter.HEADER, fromNginx))
                .andReturn();
        assertThat(kept.getResponse().getHeader(RequestLogFilter.HEADER)).isEqualTo(fromNginx);
        logLineOf(output, fromNginx);

        MvcResult replaced = mockMvc.perform(get("/api/universities").param("domain", "nowhere.ac.kr")
                        .header(RequestLogFilter.HEADER, "\"},\"level\":\"ERROR"))
                .andReturn();
        assertThat(replaced.getResponse().getHeader(RequestLogFilter.HEADER)).matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("로그인한 요청은 로그에 사용자 id가 붙고, 요청이 끝나면 MDC가 비워진다")
    void addsUserIdAndClearsMdc(CapturedOutput output) throws Exception {
        long seq = System.nanoTime();
        University university = universityRepository.save(new University("로그대학교" + seq, "log" + seq + ".ac.kr"));
        User user = userRepository.save(new User(university, "u" + seq + "@log" + seq + ".ac.kr", "hashed-password", "log" + seq));

        MvcResult result = mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + jwtProvider.createAccessToken(user.getId())))
                .andExpect(status().isOk())
                .andReturn();

        String line = logLineOf(output, result.getResponse().getHeader(RequestLogFilter.HEADER));
        assertThat(line).contains("\"userId\":\"" + user.getId() + "\"", "\"uri\":\"/api/auth/me\"");
        // MockMvc는 이 테스트 스레드에서 요청을 돌린다. 서버에서는 이 스레드가 다음 요청에 다시 쓰인다
        assertThat(MDC.getCopyOfContextMap()).as("요청이 끝난 뒤 MDC").isNullOrEmpty();
    }

    @Test
    @DisplayName("Prometheus가 긁는 /actuator는 요청 로그를 남기지 않는다")
    void skipsActuator(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());

        assertThat(output.getOut()).doesNotContain("\"uri\":\"/actuator/health\"");
    }
}
