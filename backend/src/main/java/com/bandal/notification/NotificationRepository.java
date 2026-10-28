package com.bandal.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

// 알림 테이블을 읽고 쓰는 곳. 쓰는 쪽은 둘로 나뉜다
// - worker(알림 컨슈머): insertIfAbsent로 알림을 넣는다
// - 백엔드(알림함 API): 목록, 개수, 읽음 표시. 모두 userId를 조건에 넣어 "내 알림"만 건드린다
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // ===== worker가 쓰는 것 =====

    // 알림 한 줄을 넣는다. 같은 (상태 변경 번호, 받는 사람)이 이미 있으면 에러 없이 넣지 않는다
    // 돌려주는 값: 넣었으면 1, 이미 있어서 건너뛰었으면 0
    //
    // 왜 save가 아니라 이걸 쓰나:
    // Kafka는 같은 글을 두 번 줄 수 있다(처리 뒤 책갈피를 커밋하기 전에 worker가 죽으면 다시 받는다)
    // save로 넣다 유니크 제약에 걸리면 PostgreSQL은 그 트랜잭션을 "망가진 상태"로 만든다
    // 그 뒤로는 예외를 잡아도 같은 트랜잭션 안의 다음 INSERT(다음 사람 알림)가 전부 거절된다
    // on conflict do nothing은 실패가 아니라 "조용히 안 넣기"라서 트랜잭션이 멀쩡하다
    //
    // JPQL에는 on conflict가 없어서 SQL을 그대로 쓰는 nativeQuery다. 그래서 테이블과 칸 이름(snake_case)을 쓴다
    // INSERT, UPDATE, DELETE처럼 줄을 바꾸는 쿼리에는 @Modifying을 붙여야 한다
    // nativeQuery는 enum을 알아서 바꿔 주지 않아서, 부르는 쪽이 종류와 상태를 문자열(payload.kind().name(), payload.to().name())로 넘긴다
    @Modifying
    @Query(value = """
            insert into notification (event_id, user_id, group_order_id, store_name, kind, status, cancel_reason,
                                      amount, delivery_fee, occurred_at)
            values (:eventId, :userId, :groupOrderId, :storeName, :kind, :status, :cancelReason,
                    :amount, :deliveryFee, :occurredAt)
            on conflict (event_id, user_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("eventId") Long eventId, @Param("userId") Long userId, @Param("groupOrderId") Long groupOrderId,
                       @Param("storeName") String storeName, @Param("kind") String kind, @Param("status") String status,
                       @Param("cancelReason") String cancelReason, @Param("amount") Long amount,
                       @Param("deliveryFee") Long deliveryFee, @Param("occurredAt") Instant occurredAt);

    // ===== 알림함 API가 쓰는 것 =====

    // 내 알림 목록 한 묶음: 내 알림 중 id가 before보다 작은 줄을 최신순으로
    // 메서드 이름만으로 쿼리가 만들어진다(쿼리 메서드)
    //   findBy UserId And IdLessThan OrderByIdDesc
    //   → where user_id = ? and id < ? order by id desc
    // Pageable에는 PageRequest.of(0, size)를 넣는다. 여기서는 "몇 개까지"만 전하는 용도다
    // 돌려받는 타입이 Slice라서 Spring이 size보다 하나 더 읽어 보고, 더 있으면 hasNext를 true로 둔다
    // 테이블의 (user_id, id desc) 인덱스와 조건, 순서가 그대로 맞아서 앞에서부터 size+1줄만 읽고 멈춘다
    Slice<Notification> findByUserIdAndIdLessThanOrderByIdDesc(Long userId, Long before, Pageable pageable);

    // 내 알림 중 안 읽은(read_at이 비어 있는) 줄 수. 종 아이콘의 빨간 숫자
    // countBy로 시작하면 줄을 꺼내지 않고 select count(*)만 낸다
    // ReadAtIsNull은 read_at is null이 된다. SQL에서 "= null"은 늘 참이 아니라서 빈 칸은 is null로만 묻는다
    long countByUserIdAndReadAtIsNull(Long userId);

    // 알림 하나를 읽음으로. 돌려주는 값은 조건에 걸린 줄 수(0 또는 1)
    //
    // 주인 검사를 where에 넣었다(n.userId = :userId)
    // LEE가 주소의 id를 PARK 알림 번호로 바꿔 보내도, LEE의 userId와는 안 맞아서 한 줄도 안 걸린다(0)
    // 꺼내서 주인을 비교하는 방식보다 쿼리가 하나 적고, 비교를 빠뜨릴 곳이 없다
    //
    // coalesce(a, b)는 a가 있으면 a, 비어 있으면 b다
    // 이미 읽은 알림을 다시 눌러도 처음 읽은 시각이 그대로 남는다
    // "and n.readAt is null"을 조건에 넣는 방법도 있지만, 그러면 다시 누를 때 0줄이 되어 "남의 알림"과 답이 같아진다
    // PostgreSQL은 값이 그대로여도 조건에 걸린 줄은 센다. 그래서 0이면 정말 "내 알림함에 없는 알림"이다
    //
    // 이 쿼리는 JPQL이다. 테이블이 아니라 엔티티(Notification)와 필드 이름(readAt, userId)을 쓴다
    // 줄을 꺼내지 않고 DB에서 바로 바꾸는 UPDATE라 @Modifying이 필요하다
    @Modifying
    @Query("""
            update Notification n
            set n.readAt = coalesce(n.readAt, :now)
            where n.id = :id and n.userId = :userId
            """)
    int markRead(@Param("id") Long id, @Param("userId") Long userId, @Param("now") Instant now);

    // 모두 읽음. 내 알림 중 안 읽었고 id가 upToId 이하인 줄을 한 번에 읽음으로. 돌려주는 값은 바뀐 줄 수
    //
    // upToId는 화면이 본 가장 최신 알림 id다
    // 목록을 연 뒤 버튼을 누르기 전에 worker가 새 알림을 넣을 수 있다. 그 알림은 id가 더 커서 여기 안 걸린다
    // 그래서 화면에 뜨지도 않은 알림이 읽음이 되어 빨간 숫자 없이 사라지는 일이 없다
    //
    // 여기서는 "이미 읽은 줄은 건드리지 않는다"를 조건(readAt is null)에 넣었다
    // 하나 읽음과 달리 바뀐 줄 수로 404를 가릴 일이 없어서(0줄이어도 정상) 조건에 넣어도 된다
    // 덕분에 이미 읽은 줄의 처음 읽은 시각도 그대로 남는다
    @Modifying
    @Query("""
            update Notification n
            set n.readAt = :now
            where n.userId = :userId and n.readAt is null and n.id <= :upToId
            """)
    int markAllRead(@Param("userId") Long userId, @Param("upToId") Long upToId, @Param("now") Instant now);

    // ===== 정리 배치(Spring Batch)가 쓰는 것 =====

    // 읽은 지 오래된 알림을 지운다. 순진한 첫 버전: 조건에 맞는 줄을 한 문장으로 전부 지운다
    // 지금 운영의 정리 Job은 이걸 쓰지 않는다(아래 deleteByIdIn으로 1,000개씩). 청크와 숫자를 비교하는 실험(NotificationCleanupExperimentTest)에만 남겨 둔다
    // 1,000만 줄을 이걸로 지우면 3.7초 동안 그 줄 전부를 잠가서, 그 줄의 읽음 표시가 3.6초 기다렸다(2026-10-28 JOURNAL)
    // 돌려주는 값: 지운 줄 수
    // 지울 줄: 읽은 시각(readAt)이 cutoff(지금 - 30일)보다 이전인 줄. 안 읽은 줄(readAt이 null)은 비교가 참이 아니라 저절로 빠진다
    // JPQL의 delete는 엔티티를 하나씩 꺼내 지우지 않고 DB에 delete 한 문장을 바로 보낸다(bulk delete)
    // 그래서 100만 줄이어도 문장은 하나, 트랜잭션도 하나다.
    @Modifying
    @Query("delete from Notification n where n.readAt < :cutoff")
    int deleteReadBefore(@Param("cutoff") Instant cutoff);

    // 정리 Job의 Writer가 쓴다. Reader가 모은 id 목록(청크 하나, 최대 1,000개)의 알림을 한 문장으로 지운다
    // 돌려주는 값: 지운 줄 수
    // 위의 deleteReadBefore와 달리 조건이 아니라 id로 지운다. 한 문장이 지우는 양이 청크 크기를 넘지 않는다
    @Modifying
    @Query("delete from Notification n where n.id in :ids")
    int deleteByIdIn(@Param("ids") List<Long> ids);
}
