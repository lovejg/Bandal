package com.bandal.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;

// 보낼 것 하나. 메일 줄은 가입 트랜잭션이, 방 상태 줄은 상태를 바꾼 트랜잭션이 같이 넣고, 집배원이 꺼내 보낸다(메일 서버 또는 Kafka)
// 상태는 셋(대기, 보냄, 실패). 다시 시도와 찜은 nextAttemptAt을 미루는 것으로 한다
// 찜이란, 보내기 전에 "이 줄은 내가 보낼게~" 이렇게 표시해두는 것
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxMessage {

    // 이만큼 시도가 실패하면 실패로 끝낸다
    public static final int MAX_ATTEMPTS = 5;
    // 찜한 뒤 이 시간 동안은 다른 집배원이 이 줄을 가져가지 않는다. 보내다 서버가 죽으면 이 시간 뒤에 다시 찜할 수 있다
    public static final Duration CLAIM_LEASE = Duration.ofMinutes(5);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private OutboxType type;

    // 메일 받을 사람. 사용자 객체를 불러올 일이 없어서 연관관계 없이 숫자로 둔다. 방 상태 줄에는 없다(null)
    private Long userId;

    // 방 상태 줄의 방 id. Kafka에 넣을 때 키로 쓴다. 메일 줄에는 없다(null)
    private Long groupOrderId;

    // 방 상태 줄의 내용(JSON 문자열). DB에는 jsonb로 들어가고, 그대로 Kafka 메시지가 된다. 메일 줄에는 없다(null)
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private OutboxStatus status;

    // 보내기를 시도한 횟수(찜할 때마다 하나씩 는다)
    private int attempts;

    // 이 시각부터 다시 찜해도 된다
    @Column(nullable = false)
    private Instant nextAttemptAt;

    @Column(nullable = false)
    private Instant createdAt;

    // 보낸 시각. 보내기 전에는 null
    private Instant sentAt;

    // 만들 때 대기로 시작하고, 바로 보낼 차례
    // 밖에서는 아래 mail, groupOrderStatus로만 만든다. 종류마다 꼭 있어야 하는 값이 다르기 때문이다
    private OutboxMessage(OutboxType type, Long userId, Long groupOrderId, String payload, Instant now) {
        this.type = type;
        this.userId = userId;
        this.groupOrderId = groupOrderId;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    // 메일 outbox 줄 만들기
    public static OutboxMessage mail(OutboxType type, Long userId, Instant now) {
        if(type == OutboxType.GROUP_ORDER_STATUS_CHANGED) {
            throw new IllegalArgumentException("메일 종류가 아닙니다: " + type);
        }
        if(userId == null) {
            throw new IllegalArgumentException("메일을 받을 사용자가 없습니다");
        }
        return new OutboxMessage(type, userId, null, null, now);
    }

    // 방 status outbox 줄 만들기
    public static OutboxMessage groupOrderStatus(Long groupOrderId, String payload, Instant now) {
        if(groupOrderId == null) {
            throw new IllegalArgumentException("방 id가 없습니다");
        }
        if(payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("보낼 내용이 없습니다");
        }
        return new OutboxMessage(OutboxType.GROUP_ORDER_STATUS_CHANGED, null, groupOrderId, payload, now);
    }

    // 찜하기. 찜 시간만큼 미룬다. 아직 보낸 게 아니다
    public void claim(Instant now) {
        if (this.status != OutboxStatus.PENDING) {
            throw new IllegalStateException("대기 중인 메시지가 아닙니다");
        }
        this.attempts++;
        this.nextAttemptAt = now.plus(CLAIM_LEASE);
    }

    // 보내기에 성공
    public void markSent(Instant now) {
        if (this.status != OutboxStatus.PENDING) {
            throw new IllegalStateException("대기 중인 메시지가 아닙니다");
        }
        this.status = OutboxStatus.SENT;
        this.sentAt = now;
    }

    // 보내기에 실패했다. 최대 횟수면 실패로 끝내고, 아니면 retryBase × 2^(시도-1) 뒤로 미룬다(retryBase는 다시 시도 간격 30초)
    public void markFailed(Instant now, Duration retryBase) {
        if (this.status != OutboxStatus.PENDING) {
            throw new IllegalStateException("대기 중인 메시지가 아닙니다");
        }
        if(attempts >= MAX_ATTEMPTS) {
            this.status = OutboxStatus.FAILED;
            return;
        }
        // 지수 백오프(exponential backoff). 실패할 때마다 2배씩 더 기다린다
        this.nextAttemptAt = now.plus(retryBase.multipliedBy(1L << (attempts - 1)));
    }
}
