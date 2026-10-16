package com.bandal.grouporder.dto;

import java.util.List;

// 내 방 목록. 진행 중 탭은 나눠 주지 않아서 hasNext가 늘 false다
public record MyGroupOrderListResponse(
        List<MyGroupOrderResponse> rooms,
        boolean hasNext
) {
}
