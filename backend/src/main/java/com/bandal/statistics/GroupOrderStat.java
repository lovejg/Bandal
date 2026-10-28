package com.bandal.statistics;

import com.bandal.grouporder.CancelType;
import com.bandal.grouporder.GroupOrderStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

// 끝난 방 하나의 통계용 사실. 통계 컨슈머가 배달완료, 취소 글을 받으면 방 id로 덮어쓴다
//
// 숫자(성사 수, 아낀 배달비 합계)를 저장하지 않는 이유:
// 숫자에 더하면 같은 글을 두 번 받거나 되감을 때 두 번 더해진다(첫 버전에서 겪었다)
// 방마다 한 줄을 덮어쓰면 몇 번 받아도 같은 줄이 같은 값으로 남는다(멱등)
// 지표는 이 줄들을 볼 때 SQL로 센다. 계산식이 틀렸으면 쿼리만 고치면 지난 숫자까지 고쳐진다
//
// 쓰기는 리포지토리의 upsert(nativeQuery)로만 한다. 엔티티는 테스트와 조회에서 꺼내 볼 때 쓴다
@Entity
@Table(name = "group_order_stat")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupOrderStat {

    // 방 id가 곧 기본 키. 방 하나에 줄 하나
    @Id
    private Long groupOrderId;

    private Long pickupSpotId;

    // DELIVERED(성사) 또는 CANCELED
    @Enumerated(EnumType.STRING)
    private GroupOrderStatus outcome;

    // 취소일 때만
    @Enumerated(EnumType.STRING)
    private CancelType cancelType;

    // 정산을 시작한 방만 있다
    private Long deliveryFee;

    // 방장 포함
    private int participantCount;

    private Instant finishedAt;

    // 이 줄을 쓴 상태 변경 번호. 더 작은 번호의 글은 이 줄을 덮어쓰지 못한다
    private Long eventId;
}
