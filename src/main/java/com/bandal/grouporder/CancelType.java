package com.bandal.grouporder;

// 방이 어떻게 취소됐는지. 나중에 방장 신뢰도 감점을 정할 때 쓴다. (ADR-021)
public enum CancelType {
    // 방장이 모집중에 취소했다. 피해가 작다
    HOST_WHILE_RECRUITING,
    // 방장이 마감 뒤에 취소했다. 참여자들은 주문을 기다리고 있었다
    HOST_AFTER_CLOSED,
    // 마감 시각에 인원이나 금액이 모자라 자동 취소됐다. 방장 잘못이 아니다
    DEADLINE_UNMET
}
