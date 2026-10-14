package com.bandal.participation.dto;

import com.bandal.participation.OrderItem;

import java.util.List;

// 메뉴를 담고, 고치고, 뺀 뒤에 돌려주는 응답
// 화면에서 바뀌는 두 곳을 한 번에 다시 그릴 수 있게 한다.
// 그 참여의 메뉴 목록 + 그 사람 합계, 그리고 합계 바에 쓰는 방 전체 합계
public record ParticipationItemsResponse(
        Long participationId,
        List<OrderItemResponse> items,
        long participationTotal,
        long groupOrderTotal
) {

    // 그 사람 합계는 이미 꺼낸 메뉴들로 자바에서 더한다. 쿼리를 하나 아낀다
    // 방 합계는 다른 사람 메뉴까지 필요해서 서비스가 DB에서 세어 넘긴다
    public static ParticipationItemsResponse of(Long participationId, List<OrderItem> items, long groupOrderTotal) {
        return new ParticipationItemsResponse(
                participationId,
                items.stream().map(OrderItemResponse::of).toList(),
                items.stream().mapToLong(OrderItem::getAmount).sum(),
                groupOrderTotal
        );
    }
}
