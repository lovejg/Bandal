package com.bandal.notification;

import com.bandal.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실험: 읽은 지 30일 지난 알림 100만(또는 1,000만) 줄을 지울 때, 한 문장 삭제와 청크 Job(1,000개씩)이 어떻게 다른가
// 숫자를 재서 찍는 실험이라 평소 테스트에서는 돌지 않는다. 돌릴 때:
//   BANDAL_EXPERIMENT=true BANDAL_EXPERIMENT_ROWS=10000000 ./gradlew test --rerun --tests '*CleanupExperiment*'
//   --rerun: 줄 수(환경 변수)만 바꾸면 Gradle이 바뀐 줄 모르고 지난 결과를 그대로 쓴다(2026-10-28 겪음)
// 결과는 "[실험]"으로 시작하는 줄로 찍힌다(테스트 결과 XML의 system-out)
//
// 보는 것:
// 1. 지우는 데 걸린 시간, 쏟아진 변경 기록(WAL) 양
// 2. 지우는 동안 다른 사용자 요청: 지울 줄의 읽음 표시(기다리나), 새 알림 넣기(안 기다리나), 알림 목록 읽기(안 기다리나)
// 3. 중간에 끊기면 무엇이 남나, 다시 돌리면 어디서부터 하나
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@EnabledIfEnvironmentVariable(named = "BANDAL_EXPERIMENT", matches = "true")
class NotificationCleanupExperimentTest {

    // 지울 줄 수. 기본 100만, BANDAL_EXPERIMENT_ROWS로 바꿔 규모를 키워 본다
    static final int OLD_READ = Integer.parseInt(System.getenv().getOrDefault("BANDAL_EXPERIMENT_ROWS", "1000000"));
    static final int KEEP = 200_000;
    static final int USERS = 10_000;

    @Autowired
    NotificationRepository notificationRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    Job notificationCleanupJob;

    final Instant now = Instant.now();
    final Instant cutoff = now.minus(Duration.ofDays(30));

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("truncate notification");
        // generate_series로 DB 안에서 한 번에 만든다. 자바에서 100만 번 insert하면 그것만 몇 분이다
        // 지울 줄: 40일 전에 읽음. 사용자 1만 명에게 고르게
        jdbcTemplate.update("""
                insert into notification (event_id, user_id, group_order_id, store_name, status, occurred_at, read_at)
                select g, g % ?, g / 10, '실험 가게', 'DELIVERED', ?::timestamptz - interval '41 days', ?::timestamptz - interval '40 days'
                from generate_series(1, ?) g
                """, USERS, now.toString(), now.toString(), OLD_READ);
        // 남길 줄: 안 읽음, 또는 어제 읽음
        jdbcTemplate.update("""
                insert into notification (event_id, user_id, group_order_id, store_name, status, occurred_at, read_at)
                select g, g % ?, g / 10, '실험 가게', 'DELIVERED', ?::timestamptz - interval '2 days',
                       case when g % 2 = 0 then ?::timestamptz - interval '1 day' end
                from generate_series(?, ?) g
                """, USERS, now.toString(), now.toString(), OLD_READ + 1, OLD_READ + KEEP);
        jdbcTemplate.execute("analyze notification");
    }

    long remainingOld() {
        return jdbcTemplate.queryForObject("select count(*) from notification where read_at < ?::timestamptz",
                Long.class, cutoff.toString());
    }

    static void report(String format, Object... args) {
        System.out.println("[실험] " + String.format(format, args));
    }

    // 걸린 시간(ms)을 재며 돌린다
    static long millis(Runnable r) {
        long start = System.nanoTime();
        r.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    @DisplayName("100만 줄을 한 문장으로 지운다: 걸린 시간, WAL, 그동안 다른 요청이 기다린 시간")
    void deleteAtOnce() throws Exception {
        // 지울 줄 하나(40일 전에 읽은 알림)와 그 주인
        Long targetId = jdbcTemplate.queryForObject("select min(id) from notification", Long.class);
        Long targetUser = jdbcTemplate.queryForObject("select user_id from notification where id = ?", Long.class, targetId);
        String walBefore = jdbcTemplate.queryForObject("select pg_current_wal_lsn()::text", String.class);

        // 1. 지우기를 다른 스레드에서 시작한다
        long[] deleted = new long[1];
        CompletableFuture<Long> deleting = CompletableFuture.supplyAsync(() -> millis(() ->
                deleted[0] = transactionTemplate.execute(s -> (long) notificationRepository.deleteReadBefore(cutoff))));

        // 2. 지우기가 줄을 잡을 때쯤 사용자 요청 셋을 보낸다
        // 처음엔 0.5초 뒤에 보냈는데 100만 줄 지우기가 0.38초에 끝나 겹치지 않았다(2026-10-28). 0.1초로 당겼다
        Thread.sleep(100);
        // 사용자가 그 오래된 알림을 다시 눌렀다(읽음 표시). 지우기가 잡은 줄이라 기다릴 것이다
        int[] marked = new int[1];
        CompletableFuture<Long> marking = CompletableFuture.supplyAsync(() -> millis(() ->
                marked[0] = transactionTemplate.execute(s -> notificationRepository.markRead(targetId, targetUser, now))));
        // worker가 새 알림을 넣었다. 다른 줄이라 기다리지 않을 것이다
        long insertMs = millis(() -> transactionTemplate.execute(s -> notificationRepository.insertIfAbsent(
                9_999_999L, 1L, 1L, "새 가게", "STATUS_CHANGED", "CLOSED", null, null, null, now)));
        // 다른 사용자가 알림 목록을 봤다. 읽기는 잠금을 기다리지 않는다(MVCC: 지우기 전 모습을 본다)
        long listMs = millis(() -> jdbcTemplate.queryForList(
                "select id from notification where user_id = ? order by id desc limit 20", 7L));
        boolean deleteStillRunning = !deleting.isDone();

        long deleteMs = deleting.get(10, TimeUnit.MINUTES);
        long markMs = marking.get(10, TimeUnit.MINUTES);
        long walBytes = jdbcTemplate.queryForObject("select pg_wal_lsn_diff(pg_current_wal_lsn(), ?::pg_lsn)::bigint",
                Long.class, walBefore);

        report("지운 줄 %,d / 걸린 시간 %,d ms / WAL %,d MB", deleted[0], deleteMs, walBytes / 1024 / 1024);
        report("사용자 요청을 보낸 순간 지우기가 아직 도는 중: %s", deleteStillRunning);
        report("지울 줄의 읽음 표시: %,d ms 기다림, 바뀐 줄 %d (0이면 기다린 끝에 줄이 이미 없었다 → API는 404)", markMs, marked[0]);
        report("새 알림 넣기: %,d ms / 알림 목록 읽기: %,d ms", insertMs, listMs);

        assertThat(deleted[0]).isEqualTo(OLD_READ);
        assertThat(remainingOld()).isZero();
    }

    @Test
    @DisplayName("한 문장 지우기가 중간에 끊기면(시간 초과) 지운 줄이 하나도 남지 않는다(전부 롤백)")
    void interrupted() {
        // 배포나 서버 재시작으로 끊긴 것을 시간 초과로 흉내 낸다
        assertThatThrownBy(() -> transactionTemplate.execute(s -> {
            // 처음엔 1초였는데 100만 줄이 그 안에 다 지워져 끊기지 않았다. 0.1초로 줄였다
            jdbcTemplate.execute("set local statement_timeout = '100ms'");
            return notificationRepository.deleteReadBefore(cutoff);
        })).isInstanceOf(Exception.class);

        long left = remainingOld();
        report("0.1초에서 끊은 뒤 남은 지울 줄: %,d / %,d (처음부터 다시 해야 한다)", left, OLD_READ);
        assertThat(left).isEqualTo(OLD_READ);
    }

    // ===== 청크 Job(1,000개씩)으로 같은 줄을 지운다. 위 한 문장 삭제와 숫자를 비교한다 =====

    // 지울 줄의 id는 [첫 id, 첫 id + OLD_READ) 구간이다(seed가 지울 줄부터 넣는다). truncate는 id 번호를 되돌리지 않아 매번 첫 id를 다시 읽는다
    long firstOldId() {
        return jdbcTemplate.queryForObject("select min(id) from notification", Long.class);
    }

    JobExecution runJob(LocalDate date) throws Exception {
        return jobOperator.start(notificationCleanupJob, new JobParametersBuilder().addLocalDate("date", date).toJobParameters());
    }

    static long jobMillis(JobExecution execution) {
        return Duration.between(execution.getStartTime(), execution.getEndTime()).toMillis();
    }

    @Test
    @DisplayName("청크 Job으로 지운다: 걸린 시간, WAL, 그동안 지울 줄의 읽음 표시가 기다린 가장 긴 시간")
    void chunkJob() throws Exception {
        // 오늘 날짜로 돌린다. 기준 = 오늘 0시(한국) - 30일. 40일 전에 읽은 줄은 지우고, 어제 읽은 줄은 남긴다
        LocalDate date = LocalDate.now(NotificationCleanupJobConfig.ZONE);
        long firstId = firstOldId();
        String walBefore = jdbcTemplate.queryForObject("select pg_current_wal_lsn()::text", String.class);

        CompletableFuture<JobExecution> job = CompletableFuture.supplyAsync(() -> {
            try {
                return runJob(date);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Job이 도는 동안 20ms마다 지울 줄 하나를 골라 읽음 표시를 보낸다. 그 줄이 지금 지우는 청크 안에 있으면 기다린다
        // 이미 지운 줄이면 바로 0줄, 아직 안 닿은 줄이면 잠금이 없어 바로 1줄이다
        List<Long> waits = new ArrayList<>();
        long listMax = 0;
        while (!job.isDone()) {
            long id = firstId + ThreadLocalRandom.current().nextLong(OLD_READ);
            Long user = jdbcTemplate.query("select user_id from notification where id = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, id);
            if (user != null) {
                waits.add(millis(() -> transactionTemplate.execute(s -> notificationRepository.markRead(id, user, now))));
            }
            listMax = Math.max(listMax, millis(() -> jdbcTemplate.queryForList(
                    "select id from notification where user_id = ? order by id desc limit 20", 7L)));
            Thread.sleep(20);
        }
        JobExecution execution = job.get();
        StepExecution step = execution.getStepExecutions().iterator().next();
        long walBytes = jdbcTemplate.queryForObject("select pg_wal_lsn_diff(pg_current_wal_lsn(), ?::pg_lsn)::bigint",
                Long.class, walBefore);
        long maxWait = waits.stream().mapToLong(Long::longValue).max().orElse(0);
        long waited = waits.stream().filter(w -> w > 50).count();

        report("[청크 Job] 지운 줄 %,d / 걸린 시간 %,d ms / 커밋 %,d번 / WAL %,d MB", step.getWriteCount(), jobMillis(execution),
                step.getCommitCount(), walBytes / 1024 / 1024);
        report("[청크 Job] 지울 줄 읽음 표시 %,d번 보냄: 가장 오래 기다린 것 %,d ms, 50ms 넘게 기다린 것 %d번 / 목록 읽기 가장 느린 것 %,d ms",
                waits.size(), maxWait, waited, listMax);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(remainingOld()).isZero();
    }

    @Test
    @DisplayName("청크 Job이 절반쯤에서 끊기면 앞 청크는 지워진 채 남고, 다시 돌리면 끊긴 곳부터 이어 간다")
    void chunkJobInterrupted() throws Exception {
        // 어제 날짜로 돌린다(위 chunkJob의 오늘과 겹치지 않게). 어제 0시 - 30일이어도 40일 전에 읽은 줄은 지우고 어제 읽은 줄은 남는다
        LocalDate date = LocalDate.now(NotificationCleanupJobConfig.ZONE).minusDays(1);
        // 지울 줄의 한가운데(event_id = OLD_READ / 2)를 지우려 하면 DB가 에러를 낸다. 배포나 재시작으로 끊긴 것의 흉내
        jdbcTemplate.execute("""
                create or replace function fail_cleanup() returns trigger as $$
                begin
                    if old.event_id = %d then raise exception '정리 중 끊김 흉내'; end if;
                    return old;
                end $$ language plpgsql
                """.formatted(OLD_READ / 2));
        jdbcTemplate.execute("create trigger fail_cleanup before delete on notification for each row execute function fail_cleanup()");
        JobExecution first;
        try {
            first = runJob(date);
        } finally {
            jdbcTemplate.execute("drop trigger fail_cleanup on notification");
        }
        long leftAfterFail = remainingOld();

        JobExecution second = runJob(date);
        StepExecution step = second.getStepExecutions().iterator().next();

        report("[청크 Job 끊김] 첫 실행 %s, %,d ms 돌고 끊김. 남은 지울 줄 %,d / %,d (한 문장 삭제는 끊기면 전부 남았다)",
                first.getStatus(), jobMillis(first), leftAfterFail, OLD_READ);
        report("[청크 Job 끊김] 다시 돌림: %s, %,d ms, 다시 읽은 줄 %,d (끊긴 청크부터. 처음부터였다면 %,d)",
                second.getStatus(), jobMillis(second), step.getReadCount(), OLD_READ);

        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(remainingOld()).isZero();
    }
}
