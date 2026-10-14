package com.bandal.participation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

// 메뉴 담기(POST)와 고치기(PUT)가 같이 쓴다. 고치기도 폼 전체를 다시 보내서 모양이 같다
// 상한은 오타를 막으려는 것이다. 가격 90000000을 그대로 받으면 합계 바가 가득 차 보인다
public record OrderItemRequest(

        @NotBlank(message = "메뉴 이름을 적어주세요")
        @Size(max = 50, message = "메뉴 이름은 50자 이하여야 합니다")
        String menuName,

        @Size(max = 100, message = "옵션은 100자 이하여야 합니다")
        String options,

        // 옵션 금액까지 포함한 한 개 값이다. 배달앱 장바구니에 찍힌 값을 옮겨 적는다.
        // 래퍼 타입이라 값이 안 왔을 때 "가격을 적어주세요"라고 알려줄 수 있다
        @NotNull(message = "가격을 적어주세요")
        @Positive(message = "가격은 0원보다 커야 합니다")
        @Max(value = 1_000_000, message = "가격은 100만 원 이하여야 합니다")
        Long unitPrice,

        @NotNull(message = "개수를 적어주세요")
        @Min(value = 1, message = "개수는 1개 이상이어야 합니다")
        @Max(value = 99, message = "개수는 99개 이하여야 합니다")
        Integer quantity
) {
}
