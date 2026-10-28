package com.bandal.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    // 지금 보낼 수 있는 대기 줄을 차례가 먼저 된 것부터 limit개 잠가서 가져온다. 다른 집배원이 이미 잠근 줄은 기다리지 않고 건너뛴다
    // JPQL에 SKIP LOCKED와 LIMIT이 없어서 nativeQuery(PostgreSQL)로 직접 작성
    // 메일 줄은 group_order_id가 null이라 = 비교가 참이 될 수 없다. 그래서 not exists가 늘 참이고 막히지 않는다
    // 찜해서 보내는 중인 줄도 PENDING이라 뒤 줄을 막는다.
    @Query(value = """
            select * from outbox_message o
            where status = 'PENDING' and next_attempt_at <= :now
            and not exists(
                select 1 from outbox_message older
                where o.group_order_id = older.group_order_id
                and o.id > older.id
                and older.status = 'PENDING')
            order by next_attempt_at
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<OutboxMessage> findDueForUpdate(@Param("now") Instant now, @Param("limit") int limit);
}
