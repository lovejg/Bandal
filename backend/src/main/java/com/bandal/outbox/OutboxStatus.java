package com.bandal.outbox;

// outbox 줄의 상태. 다시 시도는 따로 상태가 아니라 "대기 + 미룬 시각"이다
public enum OutboxStatus {
    // 아직 못 보냈다. next_attempt_at이 지나면 집배원이 집는다
    PENDING,
    // 보냈다
    SENT,
    // 최대 횟수만큼 실패했다. 더는 집지 않는다
    FAILED
}
