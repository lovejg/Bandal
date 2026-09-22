package com.bandal.participation.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AddOrderItemRequest(

        @NotBlank(message = "메뉴 이름을 적어주세요")
        String menuName,

        String options,

        // 옵션 금액까지 포함한 한 개 값이다.
        // 래퍼 타입이라 값이 안 왔을 때 "가격을 적어주세요"라고 알려줄 수 있다
        @NotNull(message = "가격을 적어주세요")
        @Positive(message = "가격은 0원보다 커야 합니다")
        Long unitPrice,

        @NotNull(message = "개수를 적어주세요")
        @Min(value = 1, message = "개수는 1개 이상이어야 합니다")
        Integer quantity
) {
}
