package com.bandal.grouporder.dto;

import java.util.List;

// 방 목록 한 페이지.
// 전체 개수와 전체 페이지 수는 주지 않는다. 그걸 알려면 COUNT 쿼리가 한 번 더 나가는데,
// 아래로 넘기며 보는 화면에서는 "다음이 있나"만 알면 된다
public record GroupOrderListResponse(
        List<GroupOrderSummaryResponse> rooms,
        boolean hasNext
) {
}
