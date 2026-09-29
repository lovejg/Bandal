package com.bandal.settlement.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

// 방장이 배달앱에서 확인한 금액. 이걸 입력하면 정산이 시작된다 (ADR-029)
public record DeliveryFeeRequest(

        // 0은 "배달비 무료"라는 뜻이 있는 값이라 허용한다 (ADR-001)
        // 래퍼 타입이라야 값이 빠졌을 때 어느 필드인지 알려줄 수 있다 (ADR-023)
        @NotNull(message = "배달비를 적어주세요")
        @PositiveOrZero(message = "배달비는 0원 이상이어야 합니다")
        Long deliveryFee,

        // 방장이 실제로 결제한 금액. 쿠폰이나 결제수단 할인 때문에 다를 수 있다.
        // 통계용이라 정산식에는 쓰지 않는다. 선택 입력이라 null이어도 된다
        @PositiveOrZero(message = "결제 금액은 0원 이상이어야 합니다")
        Long totalPaidAmount
) {
}
