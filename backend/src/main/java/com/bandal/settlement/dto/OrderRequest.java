package com.bandal.settlement.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

// 방장의 "주문했어요". 실제 결제금액은 통계용이라 비워도 된다. 도착 예정 시각은 꼭 받는다
public record OrderRequest(

        // 쿠폰이나 할인 때문에 메뉴 합계 + 배달비와 다를 수 있다. 정산식에는 쓰지 않는다
        @PositiveOrZero(message = "결제금액은 0원 이상이어야 합니다")
        Long totalPaidAmount,

        // 배달앱이 주문 직후 알려 주는 도착 예정 시각. ISO-8601(예: 2026-10-28T10:40:00Z)
        // 지금보다 뒤이고 3시간 안인지는 엔티티가 본다
        @NotNull(message = "도착 예정 시각을 입력해주세요")
        Instant expectedArrivalAt
) {
}
