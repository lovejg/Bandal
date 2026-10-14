package com.bandal.settlement.dto;

import jakarta.validation.constraints.PositiveOrZero;

// 방장의 "주문했어요". 실제 결제금액은 통계용이라 비워도 된다
public record OrderRequest(

        // 쿠폰이나 할인 때문에 메뉴 합계 + 배달비와 다를 수 있다. 정산식에는 쓰지 않는다
        @PositiveOrZero(message = "결제금액은 0원 이상이어야 합니다")
        Long totalPaidAmount
) {
}
