package com.bandal.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    // 탈퇴한 사람에게 아직 안 보낸 메일 줄을 지운다. 남겨 두면 집배원이 메일 주소가 없는 사용자에게 보내려다 실패를 되풀이한다
    // 방 상태 줄(GROUP_ORDER_STATUS_CHANGED)은 사람이 아니라 방의 일이라 건드리지 않는다. 메일 줄만 userId가 있다
    // 집배원이 막 찜해 보내는 중인 줄을 지우면 그 줄의 보냄 기록이 갈 곳을 잃는다. 탈퇴와 그 순간이 겹치는 드문 경우라 받아들인다
    @Modifying
    @Query("DELETE FROM OutboxMessage o WHERE o.userId = :userId AND o.status = com.bandal.outbox.OutboxStatus.PENDING")
    int deletePendingMailFor(@Param("userId") Long userId);
}
