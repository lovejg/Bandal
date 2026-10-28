package com.bandal.statistics;

import com.bandal.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

// 통계 대시보드의 패널 SQL(deploy/monitoring/grafana/stats-queries.sql)을 테스트 DB에 돌려 숫자를 맞춰 본다
// Grafana 없이 돌리려고 $__timeFilter(열)를 "열 between 기간 시작 and 기간 끝"으로 바꿔 넣는다(Grafana가 하는 일과 같다)
// 쿼리는 Grafana가 쓰는 읽기 전용 역할(grafana_reader)로 돌린다. 이 역할이 못 읽는 표를 쓰면 여기서 실패한다
// 테스트마다 트랜잭션을 롤백해서 넣은 학교, 거점, 통계 줄이 남지 않는다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class StatsDashboardQueryTest {

    // 테스트(Gradle)는 backend 폴더에서 돈다
    static final Path QUERIES = Path.of("../deploy/monitoring/grafana/stats-queries.sql");
    static final Path DASHBOARD = Path.of("../deploy/monitoring/grafana/dashboards/bandal-stats.json");

    // 대시보드 기간 선택기가 "2026-10-01 ~ 2026-10-31"이라고 친다
    static final String RANGE_FROM = "2026-10-01T00:00:00Z";
    static final String RANGE_TO = "2026-10-31T00:00:00Z";
    static final String IN_RANGE = "2026-10-15T12:00:00Z";
    static final String BEFORE_RANGE = "2026-09-15T12:00:00Z";

    @Autowired
    JdbcTemplate jdbcTemplate;

    long eventId = 1;
    long roomId = 1_000;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from group_order_stat");
    }

    // "-- name: 이름" 줄로 나눈 블록들. 주석 줄을 빼고 남은 줄이 그 패널의 SQL이다
    static Map<String, String> queries() throws IOException {
        Map<String, String> blocks = new LinkedHashMap<>();
        String name = null;
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(QUERIES)) {
            if (line.startsWith("-- name:")) {
                if (name != null) blocks.put(name, String.join("\n", lines).strip());
                name = line.substring("-- name:".length()).strip();
                lines.clear();
            } else if (name != null && !line.strip().startsWith("--")) {
                lines.add(line);
            }
        }
        if (name != null) blocks.put(name, String.join("\n", lines).strip());
        return blocks;
    }

    // 블록 하나를 Grafana가 하듯 기간을 채워 grafana_reader로 돌린다
    List<Map<String, Object>> run(String name) throws IOException {
        String sql = queries().get(name);
        assertThat(sql).as("stats-queries.sql의 %s 블록이 아직 비어 있다", name).isNotBlank();
        String filled = sql.replaceAll("\\$__timeFilter\\(([^)]+)\\)",
                "$1 between '" + RANGE_FROM + "' and '" + RANGE_TO + "'");
        // set local: 이 트랜잭션 안에서만 역할을 바꾼다. 롤백되면 원래 계정으로 돌아온다
        jdbcTemplate.execute("set local role grafana_reader");
        return jdbcTemplate.queryForList(filled);
    }

    long university(String name) {
        // 이메일 도메인은 학교마다 달라야 한다(unique). 이미 있는 학교와 겹치지 않게 무작위로
        return jdbcTemplate.queryForObject("insert into university (name, email_domain) values (?, ?) returning id",
                Long.class, name, UUID.randomUUID() + ".ac.kr");
    }

    long spot(long universityId, String name) {
        return jdbcTemplate.queryForObject(
                "insert into pickup_spot (university_id, name, normalized_name) values (?, ?, ?) returning id",
                Long.class, universityId, name, name);
    }

    void delivered(long spotId, long deliveryFee, int people, String finishedAt) {
        stat(spotId, "DELIVERED", null, deliveryFee, people, finishedAt);
    }

    void canceled(long spotId, String cancelType, Long deliveryFee, int people, String finishedAt) {
        stat(spotId, "CANCELED", cancelType, deliveryFee, people, finishedAt);
    }

    void stat(long spotId, String outcome, String cancelType, Long deliveryFee, int people, String finishedAt) {
        jdbcTemplate.update("""
                insert into group_order_stat
                    (group_order_id, pickup_spot_id, outcome, cancel_type, delivery_fee, participant_count, finished_at, event_id)
                values (?, ?, ?, ?, ?, ?, ?::timestamptz, ?)
                """, roomId++, spotId, outcome, cancelType, deliveryFee, people, finishedAt, eventId++);
    }

    @Test
    @DisplayName("거점별 성사율: 기간 안에 끝난 방만, 학교 이름과 함께, 끝난 방이 많은 거점부터")
    void successRate() throws IOException {
        long ga = university("가대학");
        long na = university("나대학");
        long gaDorm = spot(ga, "기숙사 로비");
        long gaLibrary = spot(ga, "도서관 앞");
        // 이름이 같은 다른 학교의 거점. 학교로 나누지 않으면 가대학 기숙사와 합쳐진다
        long naDorm = spot(na, "기숙사 로비");

        delivered(gaDorm, 3_000, 4, IN_RANGE);
        delivered(gaDorm, 3_000, 3, IN_RANGE);
        delivered(gaDorm, 2_000, 2, IN_RANGE);
        canceled(gaDorm, "DEADLINE_UNMET", null, 1, IN_RANGE);
        // 기간 밖. 세지 않는다
        delivered(gaDorm, 3_000, 4, BEFORE_RANGE);
        delivered(gaLibrary, 3_000, 2, IN_RANGE);
        canceled(naDorm, "HOST_WHILE_RECRUITING", null, 2, IN_RANGE);

        List<Map<String, Object>> rows = run("success_rate");

        assertThat(rows).extracting(r -> r.get("school"), r -> r.get("spot"),
                        r -> ((Number) r.get("delivered")).longValue(), r -> ((Number) r.get("finished")).longValue(),
                        r -> ((Number) r.get("rate")).doubleValue())
                .containsExactlyInAnyOrder(
                        tuple("가대학", "기숙사 로비", 3L, 4L, 75.0),
                        tuple("가대학", "도서관 앞", 1L, 1L, 100.0),
                        tuple("나대학", "기숙사 로비", 0L, 1L, 0.0));
        // 끝난 방이 가장 많은 거점이 맨 위
        assertThat(rows.get(0).get("spot")).isEqualTo("기숙사 로비");
        assertThat(rows.get(0).get("school")).isEqualTo("가대학");
    }

    @Test
    @DisplayName("아낀 배달비: 지금까지 전부는 기간과 상관없이, 기간 안은 기간 안만. 배달완료 방만 센다")
    void savedDeliveryFee() throws IOException {
        long spotId = spot(university("가대학"), "기숙사 로비");
        // 3,000원 × 3명 = 9,000원
        delivered(spotId, 3_000, 4, IN_RANGE);
        // 2,000원 × 1명 = 2,000원
        delivered(spotId, 2_000, 2, IN_RANGE);
        // 기간 밖: 4,000원 × 2명 = 8,000원. 지금까지 전부에만 들어간다
        delivered(spotId, 4_000, 3, BEFORE_RANGE);
        // 정산중에 취소된 방. 배달비가 있어도 아무도 안 먹었으니 세지 않는다
        canceled(spotId, "HOST_WHILE_SETTLING", 3_000L, 3, IN_RANGE);

        assertThat(((Number) run("saved_total").get(0).get("saved")).longValue()).isEqualTo(19_000L);
    }

    @Test
    @DisplayName("기간 안 아낀 배달비는 기간 밖에 끝난 방을 빼고 센다")
    void savedInRange() throws IOException {
        long spotId = spot(university("가대학"), "기숙사 로비");
        delivered(spotId, 3_000, 4, IN_RANGE);
        delivered(spotId, 2_000, 2, IN_RANGE);
        delivered(spotId, 4_000, 3, BEFORE_RANGE);
        canceled(spotId, "HOST_WHILE_SETTLING", 3_000L, 3, IN_RANGE);

        assertThat(((Number) run("saved_in_range").get(0).get("saved")).longValue()).isEqualTo(11_000L);
    }

    @Test
    @DisplayName("배달완료 방이 하나도 없으면 아낀 배달비는 빈칸(null)이 아니라 0이다")
    void savedWhenEmpty() throws IOException {
        assertThat(((Number) run("saved_total").get(0).get("saved")).longValue()).isZero();
        assertThat(((Number) run("saved_in_range").get(0).get("saved")).longValue()).isZero();
    }

    @Test
    @DisplayName("취소 종류별 건수: 기간 안 취소만, 많은 것부터")
    void cancelTypes() throws IOException {
        long spotId = spot(university("가대학"), "기숙사 로비");
        canceled(spotId, "DEADLINE_UNMET", null, 1, IN_RANGE);
        canceled(spotId, "DEADLINE_UNMET", null, 2, IN_RANGE);
        canceled(spotId, "HOST_WHILE_SETTLING", 3_000L, 3, IN_RANGE);
        // 기간 밖
        canceled(spotId, "HOST_WHILE_RECRUITING", null, 1, BEFORE_RANGE);
        delivered(spotId, 3_000, 4, IN_RANGE);

        List<Map<String, Object>> rows = run("cancel_types");

        assertThat(rows).extracting(r -> r.get("cancel_type"), r -> ((Number) r.get("count")).longValue())
                .containsExactly(tuple("DEADLINE_UNMET", 2L), tuple("HOST_WHILE_SETTLING", 1L));
    }

    @Test
    @DisplayName("시연 학교(.example 도메인)의 방은 어느 패널에도 세지 않는다")
    void excludesDemoSchool() throws IOException {
        long realSpot = spot(university("가대학"), "기숙사 로비");
        long demoSchool = jdbcTemplate.queryForObject(
                "insert into university (name, email_domain) values ('반달대학교', ?) returning id",
                Long.class, UUID.randomUUID() + ".example");
        long demoSpot = spot(demoSchool, "학생회관 앞");
        delivered(realSpot, 3_000, 4, IN_RANGE);
        // 시연 데이터. 시연용 방이 끝나도 진짜 숫자에 섞이면 안 된다
        delivered(demoSpot, 5_000, 5, IN_RANGE);
        canceled(demoSpot, "DEADLINE_UNMET", null, 1, IN_RANGE);

        assertThat(run("success_rate")).extracting(r -> r.get("school")).containsExactly("가대학");
        assertThat(((Number) run("saved_total").get(0).get("saved")).longValue()).isEqualTo(9_000L);
        assertThat(((Number) run("saved_in_range").get(0).get("saved")).longValue()).isEqualTo(9_000L);
        assertThat(run("cancel_types")).isEmpty();
    }

    @Test
    @DisplayName("대시보드 JSON의 SQL이 stats-queries.sql과 같다(한쪽만 고치지 않았다)")
    void dashboardMatchesQueries() throws IOException {
        Map<String, String> queries = queries();
        JsonNode dashboard = new ObjectMapper().readTree(Files.readString(DASHBOARD));

        // 패널의 쿼리 이름(refId)을 블록 이름과 같게 둔다. 공백과 줄바꿈 차이는 보지 않는다
        Map<String, String> inDashboard = new LinkedHashMap<>();
        for (JsonNode panel : dashboard.get("panels")) {
            for (JsonNode target : panel.path("targets")) {
                if ("bandal-db".equals(target.path("datasource").path("uid").asString())) {
                    inDashboard.put(target.get("refId").asString(), normalize(target.get("rawSql").asString()));
                }
            }
        }
        Map<String, String> inFile = new LinkedHashMap<>();
        queries.forEach((name, sql) -> inFile.put(name, normalize(sql)));

        assertThat(inDashboard).isEqualTo(inFile);
    }

    static String normalize(String sql) {
        return sql.strip().replaceAll("\\s+", " ");
    }
}
