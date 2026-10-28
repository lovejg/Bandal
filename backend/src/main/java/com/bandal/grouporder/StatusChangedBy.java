package com.bandal.grouporder;

// 방 상태를 누가 바꿨나. 알림을 바꾼 사람에게는 보내지 않으려고 기록한다
public enum StatusChangedBy {
    // 방장이 눌러서(마감, 취소, 정산 시작, 주문, 배달)
    HOST,
    // 스케줄러가 시각을 보고(마감 시각 처리, 오래 멈춘 방 넘기기)
    SYSTEM
}
