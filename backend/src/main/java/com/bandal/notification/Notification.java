package com.bandal.notification;

import com.bandal.grouporder.GroupOrderChangeKind;
import com.bandal.grouporder.GroupOrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

// 앱 안 알림 한 줄. 받는 사람 한 명에게 상태 변경 하나
// 예: 마라탕 방이 정산중이 되면 참여자 LEE, PARK에게 한 줄씩 두 줄이 생긴다
//
// 문장("정산이 시작됐어요")은 저장하지 않고 사실(상태, 가게 이름, 금액, 사유)만 둔다
// 문장은 화면이 만든다. 문구를 고치거나 앱을 다른 언어로 내도 저장된 알림을 바꿀 필요가 없다
//
// 사용자, 방과 연관관계(@ManyToOne)를 두지 않고 id 숫자만 둔다
// 그 순간의 사실을 옮겨 적은 기록이라 원래 행을 불러올 일이 없고, worker가 본 서비스 테이블에 기대지 않게 된다
// 방 이름이 나중에 바뀌어도 알림에는 그때의 가게 이름이 남는다
//
// 읽고 쓰는 일은 모두 리포지토리 쿼리로 한다(넣기는 on conflict, 읽음은 UPDATE)
// 그래서 엔티티에 상태를 바꾸는 메서드가 없다. 테스트와 조회에서 꺼내 볼 때 쓴다
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 상태 변경 번호(Kafka 헤더 eventId = outbox 줄 id). 받는 사람과 묶어 유니크
    // 같은 Kafka 글을 두 번 받아도 같은 번호라 한 줄만 남는다
    @Column(nullable = false)
    private Long eventId;

    // 받는 사람. 알림함 API는 모두 이 칸을 토큰의 사용자 id와 맞춰 본다
    @Column(nullable = false)
    private Long userId;

    // 알림을 누르면 화면이 이 방으로 간다
    @Column(nullable = false)
    private Long groupOrderId;

    @Column(nullable = false)
    private String storeName;

    // 무엇이 바뀌었나. 상태 변경이면 STATUS_CHANGED, 배달비 수정이면 DELIVERY_FEE_CHANGED
    // 화면은 먼저 이걸 보고, 상태 변경이면 status로 문장을 고른다
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private GroupOrderChangeKind kind;

    // 바뀐 뒤 상태. 화면이 이걸 보고 문장을 고른다(CLOSED → "마감됐어요"). 배달비 수정이면 SETTLING 그대로다
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private GroupOrderStatus status;

    // 취소일 때만. 방장이 적은 사유 또는 스케줄러가 정한 사유
    private String cancelReason;

    // 정산 시작일 때 이 사람이 보낼 돈. 정산표가 아직 없으면(마감, 모집중 취소) null
    // 배달비 수정 알림이면 새로 계산된 금액이다
    private Long amount;

    // 그때의 배달비. 정산 시작 전이면 null. 배달비 수정 알림이면 바뀐 뒤 배달비다
    private Long deliveryFee;

    // 상태가 바뀐 시각(알림이 가리키는 일이 일어난 때). worker가 늦게 읽어도 이 시각은 그대로다
    @Column(nullable = false)
    private Instant occurredAt;

    // 처음 읽은 시각. null이면 안 읽음. 다시 읽어도 처음 시각이 남는다
    private Instant readAt;

    // 테스트가 상태 변경 알림을 바로 넣을 때 쓴다(운영에서는 리포지토리의 insertIfAbsent로 넣는다)
    public Notification(Long eventId, Long userId, Long groupOrderId, String storeName,
                        GroupOrderStatus status, String cancelReason, Long amount, Instant occurredAt) {
        this(eventId, userId, groupOrderId, storeName, GroupOrderChangeKind.STATUS_CHANGED, status,
                cancelReason, amount, null, occurredAt);
    }

    public Notification(Long eventId, Long userId, Long groupOrderId, String storeName, GroupOrderChangeKind kind,
                        GroupOrderStatus status, String cancelReason, Long amount, Long deliveryFee, Instant occurredAt) {
        this.eventId = eventId;
        this.userId = userId;
        this.groupOrderId = groupOrderId;
        this.storeName = storeName;
        this.kind = kind;
        this.status = status;
        this.cancelReason = cancelReason;
        this.amount = amount;
        this.deliveryFee = deliveryFee;
        this.occurredAt = occurredAt;
    }
}
