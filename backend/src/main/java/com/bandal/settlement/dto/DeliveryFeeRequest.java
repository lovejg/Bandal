package com.bandal.settlement.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

// 방장이 배달앱에서 확인한 금액. 이걸 입력하면 정산이 시작된다 (ADR-029)
public record DeliveryFeeRequest(

        // 0은 "배달비 무료"라는 뜻이 있는 값이라 허용한다 (ADR-001)
        // 래퍼 타입이라야 값이 빠졌을 때 어느 필드인지 알려줄 수 있다 (ADR-023)
        @NotNull(message = "배달비를 적어주세요")
        @PositiveOrZero(message = "배달비는 0원 이상이어야 합니다")
        Long deliveryFee

        // 실제 결제금액은 여기서 받지 않는다. 이 시점엔 방장이 아직 결제 전이다 (ADR-042)
) {
}
