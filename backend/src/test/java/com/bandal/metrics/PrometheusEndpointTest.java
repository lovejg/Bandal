package com.bandal.metrics;

import com.bandal.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 4에서 Prometheus가 긁어 갈 숫자들이 실제로 나오는지 본다.
// 이름이 바뀌거나 빠지면 Grafana 그래프가 조용히 비어 버려서, 여기서 먼저 잡는다
@SpringBootTest
@AutoConfigureMockMvc
// 테스트에서는 숫자 내보내기(/actuator/prometheus)가 기본으로 꺼져 있다. 이 테스트만 켠다
@AutoConfigureMetrics
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "management.health.mail.enabled=false")
class PrometheusEndpointTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("응답 시간 히스토그램, 커넥션 풀, 리포지토리 시간, JVM 숫자가 나온다")
    void exposesPhase4Metrics() throws Exception {
        // 요청 하나를 보내야 응답 시간과 리포지토리 숫자가 생긴다. 캐시 적중 수는 RoomListCacheTest가 본다
        mockMvc.perform(get("/api/universities").param("domain", "snu.ac.kr")).andExpect(status().isOk());

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                // p95, p99는 구간별 개수(_bucket)가 있어야 Prometheus가 계산한다
                .andExpect(content().string(containsString("http_server_requests_seconds_bucket")))
                .andExpect(content().string(containsString("uri=\"/api/universities\"")))
                .andExpect(content().string(containsString("hikaricp_connections_active")))
                .andExpect(content().string(containsString("hikaricp_connections_pending")))
                .andExpect(content().string(containsString("spring_data_repository_invocations_seconds_bucket")))
                // Tomcat 스레드 숫자(tomcat_threads_*)는 여기서 못 본다. MockMvc는 Tomcat을 띄우지 않고 컨트롤러를 바로 부른다
                .andExpect(content().string(containsString("jvm_memory_used_bytes")))
                .andExpect(content().string(containsString("application=\"bandal\"")));
    }
}
