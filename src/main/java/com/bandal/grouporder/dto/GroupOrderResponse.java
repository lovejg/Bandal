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
        String cancelType,
        // 보는 사람의 참여 id. 참여 안 했으면 null
        // 프론트는 이 값으로 "참여하기"와 "메뉴 담기" 중 어느 버튼을 띄울지 고르고, 메뉴 주소에 쓴다
        Long myParticipationId
) {

    // 인원 수와 메뉴 합계, 내 참여 id는 엔티티가 모르는 값이라 서비스가 찾아서 넘긴다
    public static GroupOrderResponse of(GroupOrder groupOrder, long participantCount, long menuTotalAmount,
                                        Long myParticipationId) {
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
                groupOrder.getCancelType() == null ? null : groupOrder.getCancelType().name(),
                myParticipationId
        );
    }
}
