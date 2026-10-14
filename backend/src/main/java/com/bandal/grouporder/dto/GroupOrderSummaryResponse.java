package com.bandal.grouporder.dto;

import java.time.Instant;

// 방 목록의 한 줄. 참여할 방을 고르는 데 필요한 것만 담는다
// 화면은 "○○마라탕 · 제1기숙사 로비 · 8,000 / 15,000 (53%) · 2/4명 · 19:30 마감" 같은 모양이다
// 상태는 전부 모집중이라 담지 않고, 배달비·결제금액·취소 사유는 목록에서 의미가 없어서 뺐다
public record GroupOrderSummaryResponse(
        Long id,
        String storeName,
        Long pickupSpotId,
        String pickupSpotName,
        String hostNickname,
        long minOrderAmount,
        // 목록의 핵심. B가 방을 고르는 기준이 "얼마나 찼나"다
        long menuTotalAmount,
        long participantCount,
        int capacity,
        Instant deadlineAt
) {
}
