package com.bandal.notification;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

// 읽은 지 30일 지난 알림을 지우는 Spring Batch Job
// batch 컨테이너가 매일 새벽 4시 30분에 날짜 파라미터를 주고 돌린다: notificationCleanupJob date=2026-10-29
//
// 모양: Job 하나 > Step 하나 > 청크(1,000개씩)
//   Reader: 지울 알림의 id를 하나씩 읽는다
//   Writer: id가 1,000개 모이면 한 번에 지운다. 청크 하나가 트랜잭션 하나라 1,000개마다 커밋한다
//   중간에 실패해도 앞에서 커밋한 청크는 지워진 채로 남는다
// 실행 기록(시작, 끝, 읽은 수, 지운 수, 커밋 수)은 Batch가 메타 표(batch_step_execution)에 남긴다
//
// 왜 delete 한 문장이 아니라 이렇게 하나(2026-10-28 측정, JOURNAL):
//   한 문장으로 1,000만 줄을 지우면 3.6초 동안 그 줄 전부에 잠금을 쥐어서, 그 줄의 읽음 표시가 3.5초 기다렸다. WAL도 1.7GB가 한꺼번에 나왔다
//   중간에 끊기면 전부 롤백이라 처음부터 다시 지운다
//   청크로 나누면 한 번에 잠그는 건 1,000줄뿐이고, 끊겨도 앞의 청크는 지워진 채로 남는다
//   다만 매일 돌리면 한 번에 하루 치(지금 규모로 수천 줄)라 한 문장으로도 충분하다. Batch를 고른 큰 이유는 실행 기록이다(ADR-084)
@Configuration
public class NotificationCleanupJobConfig {

    public static final String JOB_NAME = "notificationCleanupJob";
    static final String STEP_NAME = "deleteOldReadNotifications";

    // 청크 하나의 크기. 트랜잭션 하나에 지우는 줄 수이자, 그 줄을 고치려는 요청이 최대로 기다리는 양
    static final int CHUNK_SIZE = 1_000;
    // 읽은 지 이만큼 지난 알림을 지운다
    static final Duration KEEP_AFTER_READ = Duration.ofDays(30);
    // 날짜 파라미터를 한국 시간 0시로 읽는다. 서버 시계(UTC)와 상관없이 같은 기준이 나온다
    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    // 기준 시각: 실행 날짜 0시(한국 시간)에서 30일 전. 이보다 먼저 읽은 알림을 지운다
    // 청크마다 "지금"을 새로 재지 않고 날짜에서 계산한다. 실패 뒤 같은 날짜로 다시 돌려도 기준이 같다
    static Instant cutoff(LocalDate date) {
        return date.atStartOfDay(ZONE).toInstant().minus(KEEP_AFTER_READ);
    }

    // Job: Step을 차례로 돌리는 묶음. 지금은 Step 하나다
    // JobRepository: 실행 기록을 메타 표에 읽고 쓰는 곳. Spring Boot가 만들어 준다
    // 같은 날짜(식별 파라미터)로 이미 성공한 Job은 다시 돌지 않는다(JobInstanceAlreadyCompleteException). 하루에 한 번이 여기서 지켜진다
    @Bean
    Job notificationCleanupJob(JobRepository jobRepository, Step deleteOldReadNotifications) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(deleteOldReadNotifications)
                .build();
    }

    // Step: 청크 방식. chunk(크기)에서 Reader가 읽은 id를 1,000개 모아 Writer에 넘긴다
    // <Long, Long>: Reader가 내놓는 것과 Writer가 받는 것(둘 다 알림 id). 사이에서 바꾸는 Processor는 없다
    // transactionManager: 청크마다 이걸로 트랜잭션을 열고 커밋한다. JPA와 같은 것이라 Writer의 리포지토리 delete도 그 안에서 돈다
    // 청크가 커밋될 때마다 Reader의 "어디까지 읽었나"(마지막 id)도 실행 기록(batch_step_execution_context)에 같이 적힌다
    @Bean
    Step deleteOldReadNotifications(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                    JdbcPagingItemReader<Long> oldReadNotificationIds, ItemWriter<Long> notificationDeleter) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<Long, Long>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(oldReadNotificationIds)
                .writer(notificationDeleter)
                .build();
    }

    // Reader: 지울 알림 id를 1,000개씩(한 쪽) 읽어 하나씩 내놓는다. "마지막으로 읽은 id 다음부터" 읽는 키셋 방식이다
    // @StepScope: Step이 시작할 때 만든다. 그래야 그 실행의 파라미터(jobParameters['date'])를 받을 수 있다
    //
    // 첫 버전은 JpaPagingItemReader였다. 쪽을 "몇 번째부터"(offset)로 읽는다: 1쪽 = 0번째부터, 2쪽 = 1,000번째부터
    // 이 Job은 읽은 줄을 바로 지운다. 1쪽을 지우면 뒤 줄이 앞으로 당겨져서, 2쪽(1,000번째부터)은 당겨진 줄을 건너뛴다
    // → 2,500줄 중 1,500줄만 지우고 COMPLETED로 끝났다. 실행 기록도 1,500을 읽었다고 남겨 기록으로는 몰랐다(2026-10-28 JOURNAL)
    //
    // 키셋은 몇 번째인지가 아니라 값(id)으로 이어 간다. Batch가 쪽마다 이런 SQL을 만든다:
    //   1쪽:      select id from notification where read_at < ? order by id limit 1000
    //   2쪽부터:  select id from notification where (read_at < ?) and (id > 앞 쪽의 마지막 id) order by id limit 1000
    //   앞 줄이 지워져도 "마지막 id보다 큰 것"은 그대로라 건너뛰지 않는다. 몇 번째인지 세느라 앞줄을 훑지도 않는다
    //
    // 실패 뒤 다시 돌리면: 마지막으로 커밋한 청크의 마지막 id가 실행 기록에 있어서 그 다음부터 읽는다(이어서 하기)
    // 이 Job은 다시 해도 결과가 같은 일(멱등)이라 처음부터 해도 되지만, 이어서 하면 지운 앞부분을 다시 훑지 않는다
    //
    // 반환 타입을 ItemReader로 줄이지 않는다: @StepScope 빈은 겉껍데기(프록시)로 주입되는데, 타입이 ItemReader면
    // Batch가 이 Reader의 열기/닫기(ItemStream)를 몰라 열지 않은 채 읽으려 한다고 알려져 있다(직접 보지는 않았다)
    @Bean
    @StepScope
    JdbcPagingItemReader<Long> oldReadNotificationIds(DataSource dataSource,
                                                      @Value("#{jobParameters['date']}") LocalDate date) throws Exception {
        return new JdbcPagingItemReaderBuilder<Long>()
                // 실행 기록에서 이 Reader의 "어디까지 읽었나"를 이 이름으로 저장한다
                .name("oldReadNotificationIds")
                .dataSource(dataSource)
                .pageSize(CHUNK_SIZE)
                // JPQL이 아니라 SQL이다. 엔티티 이름(Notification, readAt) 대신 표와 칸 이름(notification, read_at)을 쓴다
                // id만 꺼낸다. Writer는 id만 있으면 지울 수 있고, 엔티티 1,000개를 메모리에 올리지 않는다
                .selectClause("select id")
                .fromClause("from notification")
                // 안 읽은 알림(read_at이 null)은 비교가 참이 아니라 저절로 빠진다
                .whereClause("where read_at < :cutoff")
                // 쪽을 나누는 기준. 이 칸으로 정렬하고 "마지막 값보다 큰 것"으로 다음 쪽을 찾는다. 겹치지 않는 값(기본 키)이어야 한다
                .sortKeys(Map.of("id", Order.ASCENDING))
                // SQL 결과 한 줄(rs)을 무엇으로 바꿀지. id 칸 하나를 Long으로
                .rowMapper((rs, rowNum) -> rs.getLong("id"))
                // JDBC(PostgreSQL 드라이버)는 Instant를 바로 받지 못해 OffsetDateTime으로 넘긴다
                .parameterValues(Map.of("cutoff", cutoff(date).atOffset(ZoneOffset.UTC)))
                .build();
    }

    // Writer: Reader가 모은 id 1,000개(Chunk)를 받아 한 번에 지운다
    // 청크 트랜잭션 안에서 불리므로 여기서 @Transactional을 붙이지 않는다
    @Bean
    ItemWriter<Long> notificationDeleter(NotificationRepository notificationRepository) {
        // chunk.getItems(): 이번 청크의 id 목록(최대 1,000개)
        // ItemWriter<Long>가 받는 건 Chunk<? extends Long>라 getItems()는 List<? extends Long>다("Long이거나 그 하위 타입의 목록")
        // List<Long>을 받는 deleteByIdIn에 바로 못 넘겨서(컴파일 에러: List<CAP#1> cannot be converted to List<Long>) List<Long>으로 복사해 넘긴다
        return chunk -> notificationRepository.deleteByIdIn(List.copyOf(chunk.getItems()));
    }
}
