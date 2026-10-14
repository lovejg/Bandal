package com.bandal.grouporder;

// 방이 어떻게 취소됐는지. 나중에 방장 신뢰도 감점을 정할 때 쓴다
public enum CancelType {
    // 방장이 모집중에 취소했다. 피해가 작다
    HOST_WHILE_RECRUITING,
    // 방장이 마감 뒤에 취소했다. 참여자들은 주문을 기다리고 있었다
    HOST_WHILE_CLOSED,
    // 방장이 정산중에 취소했다. 이미 방장 손에 돈이 가 있어서 피해가 가장 크다
    HOST_WHILE_SETTLING,
    // 마감 시각에 인원이나 금액이 모자라 자동 취소됐다. 방장 잘못이 아니다
    DEADLINE_UNMET,
    // 마감 뒤 방장이 배달비를 넣지 않아 자동 취소됐다. 돈은 오가지 않았다
    CLOSED_TIMEOUT,
    // 정산중에 방장이 주문하지 않아 자동 취소됐다. 보낸 사람이 있으면 환불 대상이다
    SETTLING_TIMEOUT
}
