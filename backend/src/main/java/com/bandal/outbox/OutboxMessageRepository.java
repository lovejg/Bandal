package com.bandal.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    // 지금 보낼 수 있는 대기 줄을 차례가 먼저 된 것부터 limit개 잠가서 가져온다. 다른 집배원이 이미 잠근 줄은 기다리지 않고 건너뛴다
    // JPQL에 SKIP LOCKED와 LIMIT이 없어서 nativeQuery(PostgreSQL)로 직접 작성
    @Query(value = """
            select * from outbox_message
            where status = 'PENDING' and next_attempt_at <= :now
            order by next_attempt_at
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<OutboxMessage> findDueForUpdate(@Param("now") Instant now, @Param("limit") int limit);
}
