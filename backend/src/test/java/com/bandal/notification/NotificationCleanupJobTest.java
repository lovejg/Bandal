package com.bandal.notification;

import com.bandal.TestcontainersConfiguration;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 알림 정리 Job을 실제로 돌려 본다. 청크(1,000개)보다 많은 2,500줄을 지워야 해서 쪽이 세 번 넘어간다
// 테스트마다 다른 날짜를 쓴다. 같은 날짜면 Batch가 "이미 끝난 실행"으로 보고 거절한다(아래 sameDateTwice)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NotificationCleanupJobTest {

    static final int OLD_READ = 2_500;
    static final int RECENT_READ = 300;
    static final int UNREAD = 200;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    Job notificationCleanupJob;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("truncate notification");
    }

    // date 날짜로 돌릴 때 지워야 할 줄, 남겨야 할 줄을 넣는다
    void seed(LocalDate date) {
        Instant cutoff = NotificationCleanupJobConfig.cutoff(date);
        insert(1, OLD_READ, cutoff.minusSeconds(86_400 * 10));
        // 기준보다 1초 뒤에 읽음. 경계 바로 안쪽이라 남는다
        insert(OLD_READ + 1, RECENT_READ, cutoff.plusSeconds(1));
        insert(OLD_READ + RECENT_READ + 1, UNREAD, null);
    }

    void insert(int fromEventId, int count, Instant readAt) {
        jdbcTemplate.update("""
                insert into notification (event_id, user_id, group_order_id, store_name, status, occurred_at, read_at)
                select g, g % 50, 1, '정리 가게', 'DELIVERED', '2026-01-01T00:00:00Z'::timestamptz, ?::timestamptz
                from generate_series(?, ?) g
                """, readAt == null ? null : readAt.toString(), fromEventId, fromEventId + count - 1);
    }

    long count(String where) {
        return jdbcTemplate.queryForObject("select count(*) from notification where " + where, Long.class);
    }

    JobParameters onDate(LocalDate date) {
        return new JobParametersBuilder().addLocalDate("date", date).toJobParameters();
    }

    @Test
    @DisplayName("기준(실행 날짜 0시 한국 시간 - 30일)은 날짜에서 계산한다")
    void cutoffFromDate() {
        // 2026-10-29 0시 KST = 2026-10-28 15:00 UTC, 그 30일 전
        assertThat(NotificationCleanupJobConfig.cutoff(LocalDate.of(2026, 10, 29)))
                .isEqualTo(Instant.parse("2026-09-28T15:00:00Z"));
    }

    @Test
    @DisplayName("읽은 지 30일 지난 알림 2,500줄을 1,000개씩 나눠 모두 지운다. 최근에 읽은 것과 안 읽은 것은 남는다")
    void deletesAllOldRead() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 29);
        seed(date);

        JobExecution execution = jobOperator.start(notificationCleanupJob, onDate(date));
        StepExecution step = execution.getStepExecutions().iterator().next();

        // 하나가 틀려도 멈추지 않고 전부 검사해서 한꺼번에 보고한다(SoftAssertions). 상태, 남은 줄, 실행 기록을 같이 본다
        SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(execution.getStatus()).as("Job 상태").isEqualTo(BatchStatus.COMPLETED);
            // 지울 줄이 하나도 남지 않아야 한다
            soft.assertThat(count("read_at < '" + NotificationCleanupJobConfig.cutoff(date) + "'::timestamptz"))
                    .as("남은 지울 줄").isZero();
            soft.assertThat(count("true")).as("남은 전체 줄").isEqualTo(RECENT_READ + UNREAD);
            // 실행 기록(batch_step_execution)에 남은 숫자. 읽은 수와 지운 수가 지울 줄 수와 같아야 한다
            soft.assertThat(step.getReadCount()).as("실행 기록의 읽은 수").isEqualTo(OLD_READ);
            soft.assertThat(step.getWriteCount()).as("실행 기록의 지운 수").isEqualTo(OLD_READ);
        });
    }

    @Test
    @DisplayName("같은 날짜로 이미 끝난 Job을 다시 돌리면 Batch가 거절한다(하루에 한 번)")
    void sameDateTwice() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 30);
        seed(date);
        jobOperator.start(notificationCleanupJob, onDate(date));

        assertThatThrownBy(() -> jobOperator.start(notificationCleanupJob, onDate(date)))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    @Test
    @DisplayName("중간 청크에서 실패하면 앞 청크는 지워진 채 남고, 같은 날짜로 다시 돌리면 실패한 곳부터 이어서 끝낸다")
    void restartsFromLastCommittedChunk() throws Exception {
        LocalDate date = LocalDate.of(2026, 11, 1);
        seed(date);
        // 배포나 DB 문제로 중간에 끊긴 것을 흉내 낸다: 1,500번째 알림을 지우려 하면 DB가 에러를 낸다
        // 그 알림은 둘째 청크(1,001~2,000번째)에 들어 있어서, 첫 청크는 커밋되고 둘째 청크는 롤백된다
        jdbcTemplate.execute("""
                create or replace function fail_cleanup() returns trigger as $$
                begin
                    if old.event_id = 1500 then raise exception '정리 중 끊김 흉내'; end if;
                    return old;
                end $$ language plpgsql
                """);
        jdbcTemplate.execute("create trigger fail_cleanup before delete on notification for each row execute function fail_cleanup()");
        String oldRows = "read_at < '" + NotificationCleanupJobConfig.cutoff(date) + "'::timestamptz";

        JobExecution first;
        try {
            first = jobOperator.start(notificationCleanupJob, onDate(date));
        } finally {
            // 원인을 고쳤다(트리거를 지웠다)
            jdbcTemplate.execute("drop trigger fail_cleanup on notification");
        }

        // 첫 실행: 실패. 첫 청크 1,000줄은 이미 커밋돼 지워진 채로 남았다(한 문장 삭제였다면 0줄)
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(count(oldRows)).isEqualTo(OLD_READ - 1_000);

        // 같은 날짜로 다시 돌린다. 실패한 Job 인스턴스라 Batch가 거절하지 않고 이어서 한다
        JobExecution second = jobOperator.start(notificationCleanupJob, onDate(date));
        StepExecution step = second.getStepExecutions().iterator().next();

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count(oldRows)).isZero();
        assertThat(count("true")).isEqualTo(RECENT_READ + UNREAD);
        // 이어서 했으니 다시 읽은 건 남은 1,500줄뿐이다. 실행 기록의 "마지막 id" 다음부터 읽었다
        assertThat(step.getReadCount()).isEqualTo(OLD_READ - 1_000);
        // 같은 Job 인스턴스(같은 날짜)의 두 번째 실행이다
        assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
    }

    @Test
    @DisplayName("지울 것이 없는 날에도 Job은 성공으로 끝난다")
    void nothingToDelete() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 31);

        JobExecution execution = jobOperator.start(notificationCleanupJob, onDate(date));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions().iterator().next().getWriteCount()).isZero();
    }
}
