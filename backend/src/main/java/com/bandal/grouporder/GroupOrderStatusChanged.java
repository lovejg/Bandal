package com.bandal.grouporder;

import java.time.Instant;

// 방에 변경이 생겼다는 기록. 엔티티가 남기고, save 때 Spring이 사건으로 내보낸다
// 처음에는 상태 변경만 담았고, 배달비 수정(상태는 그대로, 금액이 바뀜)도 같은 길로 보내려고 kind를 더했다
// 참여자와 금액은 엔티티가 모르니 여기 없다. 받아 적는 리스너가 커밋 직전에 DB에서 채운다
public record GroupOrderStatusChanged(
        Long groupOrderId,
        GroupOrderStatus from,
        GroupOrderStatus to,
        StatusChangedBy changedBy, // 누가 바꿨는지
        GroupOrderChangeKind kind, // 무엇이 바뀌었는지
        Instant occurredAt
) {

    // 상태 변경 기록. kind를 적지 않으면 상태 변경이다(엔티티의 changeStatus와 기존 테스트가 쓴다)
    public GroupOrderStatusChanged(Long groupOrderId, GroupOrderStatus from, GroupOrderStatus to,
                                   StatusChangedBy changedBy, Instant occurredAt) {
        this(groupOrderId, from, to, changedBy, GroupOrderChangeKind.STATUS_CHANGED, occurredAt);
    }
}
