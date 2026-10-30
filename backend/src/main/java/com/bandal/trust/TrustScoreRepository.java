package com.bandal.trust;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface TrustScoreRepository extends JpaRepository<TrustScoreChange, Long> {

    // 변동 한 줄을 넣는다. 같은 (글, 사람)이 이미 있으면 넣지 않는다(같은 글을 다시 읽은 경우)
    // 돌려주는 값: 넣은 줄 수(1이면 처음, 0이면 이미 반영한 글). 1일 때만 점수를 움직인다
    // JPQL에 on conflict가 없어서 SQL을 그대로 쓴다. enum은 이름 문자열로 넘긴다(알림 insertIfAbsent와 같다)
    @Modifying
    @Query(value = """
            insert into trust_score_change (event_id, user_id, group_order_id, store_name, reason, delta, occurred_at)
            values (:eventId, :userId, :groupOrderId, :storeName, :reason, :delta, :occurredAt)
            on conflict (event_id, user_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("eventId") Long eventId, @Param("userId") Long userId,
                       @Param("groupOrderId") Long groupOrderId, @Param("storeName") String storeName,
                       @Param("reason") String reason, @Param("delta") int delta,
                       @Param("occurredAt") Instant occurredAt);

    // 점수를 delta만큼 움직이되 0~100을 넘지 않게 자른다
    // 읽어서 더하고 다시 쓰지 않고 UPDATE 한 문장으로 한다. 같은 사람의 글 두 개를 동시에 처리해도 더하기가 빠지지 않는다
    // (읽고-더하고-쓰기 사이에 다른 쪽이 끼면 한쪽의 더하기가 덮인다)
    @Modifying
    @Query(value = "update users set trust_score = least(100, greatest(0, trust_score + :delta)) where id = :userId",
            nativeQuery = true)
    int addScore(@Param("userId") Long userId, @Param("delta") int delta);

    // 내 정보 화면의 최근 변동 기록
    List<TrustScoreChange> findTop20ByUserIdOrderByIdDesc(Long userId);
}
