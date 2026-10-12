package com.bandal.settlement;

// 정산중에 취소된 방의 환불 대상 줄이 지금 어디까지 왔는지. 저장하지 않고 칸을 보고 계산한다
public enum RefundStatus {
    // 대상인데 아직 아무 일도 없다
    REFUND_PENDING,
    // 방장이 입금을 못 받았다고 했다
    PAYMENT_DENIED,
    // 방장이 돌려줬다고 했다. 참여자의 확인을 기다린다
    REFUND_SENT,
    // 참여자가 돌려받았다고 했다. 끝
    REFUNDED
}
