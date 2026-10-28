package com.bandal.statistics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

// 끝난 방마다 한 줄인 통계 사실
public interface GroupOrderStatRepository extends JpaRepository<GroupOrderStat, Long> {

    // 끝난 방 한 줄을 넣는다. 같은 방 줄이 이미 있으면 새 값으로 덮어쓴다(upsert)
    // excluded는 "충돌 때문에 못 넣은, 넣으려던 줄"이다. excluded.outcome = values에 넣은 :outcome
    // 그래서 같은 글을 몇 번 받아도 줄이 같은 값으로 다시 써질 뿐 불어나지 않는다
    // 맨 끝 where: 저장된 줄의 상태 변경 번호가 넣으려는 글의 번호보다 크면 덮어쓰지 않는다(아무것도 안 하고 끝)
    // 더 오래된 글이 늦게 와서 새 결과를 덮는 걸 막는다
    // 같은 번호(<=의 =)는 덮어쓴다. 되감아 같은 글을 다시 읽는 경우다. 컨슈머가 사실을 옮기는 코드를 고친 뒤 되감으면 고친 값으로 바뀐다
    // status, cancel_type은 nativeQuery라 enum을 못 넘겨서 부르는 쪽이 문자열로 넘긴다(취소가 아니면 null)
    @Modifying
    @Query(value = """
            insert into group_order_stat (group_order_id, pickup_spot_id, outcome, cancel_type, delivery_fee,
                                          participant_count, finished_at, event_id)
            values (:groupOrderId, :pickupSpotId, :outcome, :cancelType, :deliveryFee,
                    :participantCount, :finishedAt, :eventId)
            on conflict (group_order_id) do update
            set pickup_spot_id = excluded.pickup_spot_id,
                outcome = excluded.outcome,
                cancel_type = excluded.cancel_type,
                delivery_fee = excluded.delivery_fee,
                participant_count = excluded.participant_count,
                finished_at = excluded.finished_at,
                event_id = excluded.event_id
            where group_order_stat.event_id <= excluded.event_id
            """, nativeQuery = true)
    void upsert(@Param("groupOrderId") Long groupOrderId, @Param("pickupSpotId") Long pickupSpotId,
                @Param("outcome") String outcome, @Param("cancelType") String cancelType,
                @Param("deliveryFee") Long deliveryFee, @Param("participantCount") int participantCount,
                @Param("finishedAt") Instant finishedAt, @Param("eventId") Long eventId);
}
