package com.bandal.user.dto;

import com.bandal.trust.TrustScoreChange;

import java.time.Instant;

// 신뢰도 점수 변동 한 줄. 이유(reason)는 이름 그대로 주고 문구는 화면이 정한다(알림과 같은 방식)
public record TrustScoreChangeResponse(
        Long groupOrderId,
        String storeName,
        String reason,
        int delta,
        Instant occurredAt
) {

    public static TrustScoreChangeResponse of(TrustScoreChange change) {
        return new TrustScoreChangeResponse(change.getGroupOrderId(), change.getStoreName(),
                change.getReason().name(), change.getDelta(), change.getOccurredAt());
    }
}
