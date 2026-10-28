package com.bandal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 시연 데이터 스크립트(deploy/bandal/demo-seed.sql)를 테스트 DB에 진짜 psql로 돌려 본다
// 운영과 같은 마이그레이션이 끝난 표에 넣으므로, 칸 이름이나 제약이 바뀌어 스크립트가 깨지면 여기서 먼저 드러난다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DemoSeedTest {

    // 테스트(Gradle)는 backend 폴더에서 돈다
    static final Path SCRIPT = Path.of("../deploy/bandal/demo-seed.sql");
    static final String PASSWORD = "demo-password-1";

    @Autowired
    PostgreSQLContainer postgres;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MockMvc mockMvc;

    ObjectMapper objectMapper = new ObjectMapper();

    long realUniversity;

    @BeforeEach
    void setUp() {
        // 시연 학교가 아닌 진짜 학교 하나와 그 학교 사람. 스크립트가 건드리면 안 된다
        jdbcTemplate.update("delete from users where email = 'someone@real-school.ac.kr'");
        jdbcTemplate.update("delete from university where email_domain = 'real-school.ac.kr'");
        realUniversity = jdbcTemplate.queryForObject(
                "insert into university (name, email_domain) values ('진짜대학교', 'real-school.ac.kr') returning id", Long.class);
        jdbcTemplate.update("""
                insert into users (university_id, email, password, nickname, trust_score)
                values (?, 'someone@real-school.ac.kr', 'hash', '진짜사람', 50)
                """, realUniversity);
    }

    // demo-seed.sh가 하는 것처럼 psql에 해시를 넘겨 돌린다
    ExecResult seed() throws Exception {
        postgres.copyFileToContainer(MountableFile.forHostPath(SCRIPT), "/tmp/demo-seed.sql");
        String hash = new BCryptPasswordEncoder().encode(PASSWORD);
        return postgres.execInContainer("psql", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(),
                "-q", "-v", "pw_hash='" + hash + "'", "-f", "/tmp/demo-seed.sql");
    }

    long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    String login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asString();
    }

    @Test
    @DisplayName("두 번 돌려도 같은 모양이다: 학교 둘, 계정 10개, 학교마다 모집중 5개와 정산중, 주문완료, 배달완료 하나씩")
    void seedsTwiceSameShape() throws Exception {
        for (int i = 0; i < 2; i++) {
            ExecResult result = seed();
            assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        }

        assertThat(count("select count(*) from university where email_domain like '%.example'")).isEqualTo(2);
        assertThat(count("""
                select count(*) from users us join university u on u.id = us.university_id
                where u.email_domain like '%.example' and us.email_verified_at is not null and us.account_number is not null
                """)).isEqualTo(10);
        List<Map<String, Object>> rooms = jdbcTemplate.queryForList("""
                select u.email_domain as school, g.status, count(*) as n
                from group_order g join pickup_spot sp on sp.id = g.pickup_spot_id join university u on u.id = sp.university_id
                where u.email_domain like '%.example' group by u.email_domain, g.status
                """);
        assertThat(rooms).hasSize(8);
        assertThat(rooms).filteredOn(r -> r.get("status").equals("RECRUITING"))
                .allSatisfy(r -> assertThat(((Number) r.get("n")).longValue()).isEqualTo(5));
        assertThat(rooms).filteredOn(r -> !r.get("status").equals("RECRUITING"))
                .allSatisfy(r -> assertThat(((Number) r.get("n")).longValue()).isEqualTo(1));
        // 진짜 학교 사람은 그대로다
        assertThat(count("select count(*) from users where email = 'someone@real-school.ac.kr'")).isEqualTo(1);
    }

    @Test
    @DisplayName("방의 인원 칸과 참여 줄 수가 같고, 정산표는 앱의 계산(메뉴 + 배달비 1/n, 남는 몇 원은 방장)과 같다")
    void consistentWithAppRules() throws Exception {
        assertThat(seed().getExitCode()).isZero();

        // 인원 칸은 앱이 참여, 나가기 때 같이 바꾸는 숫자다. 실제 행 수와 다르면 정원 검사가 틀린다
        assertThat(count("""
                select count(*) from group_order g
                join pickup_spot sp on sp.id = g.pickup_spot_id join university u on u.id = sp.university_id
                where u.email_domain like '%.example'
                  and g.participant_count <> (select count(*) from participation p where p.group_order_id = g.id)
                """)).isZero();
        // 줄마다 total = menu + fee_share, 줄의 메뉴 합계 = 그 사람이 담은 메뉴 합, 방의 몫을 다 더하면 배달비
        assertThat(count("select count(*) from settlement where total_amount <> menu_total_amount + fee_share")).isZero();
        assertThat(count("""
                select count(*) from settlement s
                where s.menu_total_amount <> (select coalesce(sum(i.unit_price * i.quantity), 0) from order_item i
                                              join participation p on p.id = i.participation_id
                                              where p.group_order_id = s.group_order_id and p.user_id = s.user_id)
                """)).isZero();
        assertThat(count("""
                select count(*) from group_order g
                where g.delivery_fee is not null
                  and g.delivery_fee <> (select sum(s.fee_share) from settlement s where s.group_order_id = g.id)
                """)).isZero();
        // 주문완료 방은 도착 예정이 지금보다 뒤다
        assertThat(count("""
                select count(*) from group_order g join pickup_spot sp on sp.id = g.pickup_spot_id
                join university u on u.id = sp.university_id
                where u.email_domain like '%.example' and g.status = 'ORDERED' and g.expected_arrival_at > now()
                """)).isEqualTo(2);
    }

    @Test
    @DisplayName("시연 계정으로 로그인하면 자기 학교의 모집중 방 5개가 목록에 보인다")
    void demoAccountSeesRooms() throws Exception {
        assertThat(seed().getExitCode()).isZero();

        String token = login("demo1@bandal-univ.example");

        mockMvc.perform(get("/api/group-orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms.length()").value(5));
    }
}
