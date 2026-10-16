package com.bandal.settlement.dto;

import com.bandal.settlement.RefundStatus;
import com.bandal.settlement.Settlement;

import java.time.Instant;

// 정산표 한 줄. 방장은 전원의 줄을 보고, 참여자는 자기 줄만 본다
// nickname은 송금할 때 "받는 분 통장에 표시할 내용"에 적을 문구로도 쓴다.
// 방장 통장에는 실명이 찍히는데 우리가 아는 건 닉네임이라, 이걸로 잇는다
public record SettlementLineResponse(
        Long id,
        Long userId,
        String nickname,
        boolean host,
        long menuTotalAmount,
        long feeShare,
        long totalAmount,
        Instant markedPaidAt,
        Instant confirmedPaidAt,
        // 방장이 확인을 취소한 시각. 참여자가 "확인했다가 취소했다"를 따질 근거다
        Instant confirmRevokedAt,
        // 아래 넷은 정산중에 취소된 방의 환불 기록이다
        Instant refundSentAt,
        Instant refundReceivedAt,
        Instant paymentDeniedAt,
        // 위 칸들을 서버가 정리한 상태. 화면은 이것만 보고 문구를 고른다. 환불 대상이 아니면 null
        RefundStatus refundStatus
) {

    public static SettlementLineResponse of(Settlement settlement) {
        return new SettlementLineResponse(
                settlement.getId(),
                settlement.getUser().getId(),
                settlement.getUser().getNickname(),
                settlement.isHost(),
                settlement.getMenuTotalAmount(),
                settlement.getFeeShare(),
                settlement.getTotalAmount(),
                settlement.getMarkedPaidAt(),
                settlement.getConfirmedPaidAt(),
                settlement.getConfirmRevokedAt(),
                settlement.getRefundSentAt(),
                settlement.getRefundReceivedAt(),
                settlement.getPaymentDeniedAt(),
                settlement.refundStatus()
        );
    }
}
