package com.bandal.grouporder.dto;

import com.bandal.grouporder.GroupOrder;

import java.time.Instant;

// 방장은 id만 내보낸다. host 엔티티를 그대로 담으면 비밀번호 해시와 이메일까지 나간다
public record GroupOrderResponse(
        Long id,
        Long hostId,
        Long pickupSpotId,
        String storeName,
        long minOrderAmount,
        Instant deadlineAt,
        int capacity,
        String status,
        long participantCount,
        long menuTotalAmount,
        String cancelReason,
        String cancelType
) {

    // 인원 수와 메뉴 합계는 엔티티가 모르는 값이라 서비스가 세어서 넘긴다
    public static GroupOrderResponse of(GroupOrder groupOrder, long participantCount, long menuTotalAmount) {
        return new GroupOrderResponse(
                groupOrder.getId(),
                groupOrder.getHost().getId(),
                groupOrder.getPickupSpot().getId(),
                groupOrder.getStoreName(),
                groupOrder.getMinOrderAmount(),
                groupOrder.getDeadlineAt(),
                groupOrder.getCapacity(),
                groupOrder.getStatus().name(),
                participantCount,
                menuTotalAmount,
                groupOrder.getCancelReason(),
                groupOrder.getCancelType() == null ? null : groupOrder.getCancelType().name()
        );
    }
}
