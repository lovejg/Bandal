package com.bandal.participation.dto;

import com.bandal.participation.OrderItem;
import com.bandal.participation.Participation;

import java.util.List;

// 검수 목록의 한 사람 몫
// 메뉴를 아직 안 담은 사람도 빈 목록과 0원으로 들어간다. 그래야 방장이 그 사람이 있는 줄 안다
public record ParticipantItemsResponse(
        Long participationId,
        Long userId,
        String nickname,
        boolean host,
        List<OrderItemResponse> items,
        long participationTotal
) {

    public static ParticipantItemsResponse of(Participation participation, List<OrderItem> items, boolean host) {
        return new ParticipantItemsResponse(
                participation.getId(),
                participation.getUser().getId(),
                participation.getUser().getNickname(),
                host,
                items.stream().map(OrderItemResponse::of).toList(),
                items.stream().mapToLong(OrderItem::getAmount).sum()
        );
    }
}
