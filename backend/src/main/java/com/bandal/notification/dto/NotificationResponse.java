package com.bandal.notification.dto;

import com.bandal.grouporder.GroupOrderChangeKind;
import com.bandal.grouporder.GroupOrderStatus;
import com.bandal.notification.Notification;

import java.time.Instant;

// 알림함의 한 줄. 문장은 화면이 종류, 상태, 금액을 보고 만든다
// 예: kind STATUS_CHANGED, status SETTLING, amount 12000 → "마라탕집 · 정산이 시작됐어요 · 내 몫 12,000원"
//     kind DELIVERY_FEE_CHANGED, deliveryFee 4000, amount 11000 → "배달비가 4,000원으로 바뀌었어요 · 내 몫 11,000원"
// 받는 사람(userId)과 상태 변경 번호(eventId)는 화면에 필요 없어 내보내지 않는다
// 읽은 시각(readAt) 대신 읽었는지(read)만 내보낸다. 화면은 진하게 보일지만 정하면 된다
public record NotificationResponse(
        Long id,
        Long groupOrderId,
        String storeName,
        GroupOrderChangeKind kind,
        GroupOrderStatus status,
        String cancelReason,
        Long amount,
        Long deliveryFee,
        Instant occurredAt,
        boolean read
) {
    // 엔티티를 응답 모양으로 옮긴다. 엔티티를 그대로 내보내지 않는 건 칸이 늘 때 응답이 저절로 바뀌지 않게 하려는 것이다
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getGroupOrderId(), n.getStoreName(), n.getKind(), n.getStatus(),
                n.getCancelReason(), n.getAmount(), n.getDeliveryFee(), n.getOccurredAt(), n.getReadAt() != null);
    }
}
