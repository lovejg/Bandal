package com.bandal.statistics;

import com.bandal.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Grafana가 쓰는 역할(grafana_reader)이 통계 표 셋만 읽고, 그 밖은 못 읽고, 아무것도 못 쓰는가(V10__grafana_reader.sql)
// 테스트 DB의 계정은 최고 권한이라 "set local role"로 그 트랜잭션 동안만 grafana_reader가 되어 본다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class GrafanaReaderGrantTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    void becomeReader() {
        jdbcTemplate.execute("set local role grafana_reader");
    }

    @Test
    @DisplayName("통계 표, 거점, 학교는 읽을 수 있다")
    void readsStatTables() {
        becomeReader();

        assertThat(jdbcTemplate.queryForObject("select count(*) from group_order_stat", Long.class)).isNotNull();
        assertThat(jdbcTemplate.queryForObject("select count(*) from pickup_spot", Long.class)).isNotNull();
        assertThat(jdbcTemplate.queryForObject("select count(*) from university", Long.class)).isNotNull();
    }

    @Test
    @DisplayName("사용자 표(이메일, 비밀번호 해시)는 읽을 수 없다")
    void cannotReadUsers() {
        becomeReader();

        // Spring은 이 에러를 "bad SQL grammar"로 감싼다. 실제 이유는 맨 안쪽 원인(PostgreSQL의 에러)에 있다
        assertThatThrownBy(() -> jdbcTemplate.queryForList("select email, password from users"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied for table users");
    }

    @Test
    @DisplayName("다른 앱 표(정산, 알림)도 읽을 수 없다")
    void cannotReadOtherTables() {
        becomeReader();

        assertThatThrownBy(() -> jdbcTemplate.queryForList("select * from settlement"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied for table settlement");
    }

    @Test
    @DisplayName("읽을 수 있는 표에도 쓸 수는 없다")
    void cannotWrite() {
        becomeReader();

        assertThatThrownBy(() -> jdbcTemplate.update("delete from group_order_stat"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied for table group_order_stat");
    }

    @Test
    @DisplayName("이 역할로 연 연결은 기본이 읽기 전용 트랜잭션이다")
    void readOnlyByDefault() {
        // set role로는 로그인할 때 붙는 설정이 적용되지 않아서, 역할에 붙은 설정을 직접 읽는다
        String settings = jdbcTemplate.queryForObject("""
                select array_to_string(setconfig, ',') from pg_db_role_setting
                where setrole = 'grafana_reader'::regrole and setdatabase = 0
                """, String.class);

        assertThat(settings).contains("default_transaction_read_only=on");
    }

    @Test
    @DisplayName("마이그레이션만으로는 로그인할 수 없다(비밀번호는 서버에서 grafana-reader.sh가 정한다)")
    void noLoginUntilPasswordIsSet() {
        Boolean canLogin = jdbcTemplate.queryForObject(
                "select rolcanlogin from pg_roles where rolname = 'grafana_reader'", Boolean.class);

        assertThat(canLogin).isFalse();
    }
}
