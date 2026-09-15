package com.bandal.settlement;

// 정산 계산 결과. 참여자 한 명이 얼마를 부담하는지 담는다.
public record SettlementResult(
        Long userId,

        long menuTotalAmount,

        // 배달비 1/n
        long deliveryFeeShare,

        // menuTotalAmount + deliveryFeeShare
        // 총 정산 금액(개인)
        long totalAmount
) {
}
