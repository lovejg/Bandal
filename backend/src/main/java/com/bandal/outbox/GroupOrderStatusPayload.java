package com.bandal.outbox;

import com.bandal.grouporder.CancelType;
import com.bandal.grouporder.GroupOrderChangeKind;
import com.bandal.grouporder.GroupOrderStatus;
import com.bandal.grouporder.StatusChangedBy;

import java.time.Instant;
import java.util.List;

// 방 변경 줄의 내용. JSON으로 바꿔 outbox의 payload에 넣고, 그대로 Kafka 메시지가 된다
// 받는 쪽(알림, 통계)이 본 서비스 DB를 다시 보지 않아도 되게 그 순간의 사실을 담는다. 이메일, 닉네임은 넣지 않는다
public record GroupOrderStatusPayload(
        Long groupOrderId,
        Long pickupSpotId,
        // 알림 문구에 쓴다("○○마라탕 방이 마감됐어요")
        String storeName,
        // 방장이 직접 한 일은 방장에게 알리지 않으려고. changedBy와 같이 본다
        Long hostId,
        GroupOrderStatus from,
        GroupOrderStatus to,
        // HOST면 방장이 눌러서, SYSTEM이면 스케줄러가 시각을 보고 바꿨다. SYSTEM이면 방장에게도 알린다
        StatusChangedBy changedBy,
        // 무엇이 바뀌었나. 상태 변경이면 STATUS_CHANGED, 배달비 수정이면 DELIVERY_FEE_CHANGED(from, to 둘 다 SETTLING)
        GroupOrderChangeKind kind,
        // 취소일 때만 있다
        CancelType cancelType,
        String cancelReason,
        // 정산을 시작한 뒤에만 있다. 통계의 아낀 배달비, 배달비 수정 알림("4,000원으로 바뀌었어요")에 쓴다
        Long deliveryFee,
        List<Participant> participants,
        Instant occurredAt
) {

    // kind가 없는 글은 상태 변경으로 읽는다
    // kind를 더하기 전에 붙인 글(되감기로 다시 읽는 옛 글)에는 이 칸이 없어서 JSON을 풀면 null이 들어온다
    public GroupOrderStatusPayload {
        if (kind == null) kind = GroupOrderChangeKind.STATUS_CHANGED;
    }

    // 상태 변경 payload. kind를 적지 않으면 상태 변경이다(테스트가 쓴다)
    public GroupOrderStatusPayload(Long groupOrderId, Long pickupSpotId, String storeName, Long hostId,
                                   GroupOrderStatus from, GroupOrderStatus to, StatusChangedBy changedBy,
                                   CancelType cancelType, String cancelReason, Long deliveryFee,
                                   List<Participant> participants, Instant occurredAt) {
        this(groupOrderId, pickupSpotId, storeName, hostId, from, to, changedBy, GroupOrderChangeKind.STATUS_CHANGED,
                cancelType, cancelReason, deliveryFee, participants, occurredAt);
    }

    // 참여자 한 명(방장 포함). 금액은 정산표가 있을 때만 있다(메뉴 합계 + 배달비 몫)
    public record Participant(Long userId, Long totalAmount) {
    }
}
