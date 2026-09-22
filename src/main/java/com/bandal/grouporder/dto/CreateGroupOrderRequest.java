package com.bandal.grouporder.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

// 방장은 본문이 아니라 토큰에서 읽는다. 본문으로 받으면 남을 방장으로 만들 수 있다 (ADR-024)
public record CreateGroupOrderRequest(

        @NotNull(message = "수령 거점을 골라주세요")
        Long pickupSpotId,

        @NotBlank(message = "가게 이름을 적어주세요")
        String storeName,

        // 기본형(long)으로 두면 값이 안 왔을 때 Jackson이 record를 못 만들어서
        // "본문을 읽을 수 없다"로만 끝난다. 래퍼로 두면 어느 필드가 빠졌는지 말해줄 수 있다
        @NotNull(message = "최소주문금액을 적어주세요")
        @PositiveOrZero(message = "최소주문금액은 0원 이상이어야 합니다")
        Long minOrderAmount,

        @NotNull(message = "마감 시각을 정해주세요")
        @Future(message = "마감 시각은 현재보다 뒤여야 합니다")
        Instant deadlineAt,

        @NotNull(message = "정원을 정해주세요")
        @Min(value = 2, message = "정원은 2명 이상이어야 합니다")
        Integer capacity
) {
}
