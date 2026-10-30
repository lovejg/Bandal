package com.bandal.trust;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

// 신뢰도 점수 변동 한 줄. 쓰기는 TrustScoreRepository.insertIfAbsent(SQL)로 하고, 이 엔티티는 읽기(내 변동 기록)에 쓴다
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "trust_score_change")
public class TrustScoreChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long eventId;

    private Long userId;

    private Long groupOrderId;

    private String storeName;

    @Enumerated(EnumType.STRING)
    private TrustReason reason;

    private int delta;

    private Instant occurredAt;
}
