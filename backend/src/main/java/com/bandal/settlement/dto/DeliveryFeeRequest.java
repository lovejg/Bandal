package com.bandal.settlement.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

// 방장이 배달앱에서 확인한 금액. 이걸 입력하면 정산이 시작된다
public record DeliveryFeeRequest(

        // 0은 "배달비 무료"라는 뜻이 있는 값이라 허용한다
        // 래퍼 타입이라야 값이 빠졌을 때 어느 필드인지 알려줄 수 있다
        @NotNull(message = "배달비를 적어주세요")
        @PositiveOrZero(message = "배달비는 0원 이상이어야 합니다")
        // 위쪽 상한. 동네 배달비는 대개 3천~6천 원이다. 0 하나 더 친 실수(40000)와 부풀리기를 같이 막는다
        // 방장이 아무 숫자나 넣을 수 있는 문제(배달비 증빙)를 무겁게 풀지 않고, 상한과 신뢰도 점수로 줄인다
        @Max(value = 10_000, message = "배달비는 10,000원까지 적을 수 있습니다")
        Long deliveryFee

        // 실제 결제금액은 여기서 받지 않는다. 이 시점엔 방장이 아직 결제 전이다
) {
}
