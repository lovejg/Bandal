package com.bandal.settlement.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

// 방장의 도착 예정 시각 수정. 배달앱이 늦어진다고 알려 왔을 때
public record ExpectedArrivalRequest(
        @NotNull(message = "도착 예정 시각을 입력해주세요")
        Instant expectedArrivalAt
) {
}
