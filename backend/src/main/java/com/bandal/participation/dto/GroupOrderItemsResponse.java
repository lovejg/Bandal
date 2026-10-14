package com.bandal.participation.dto;

import com.bandal.grouporder.GroupOrder;

import java.util.List;

// 방장의 검수 목록. 참여자마다 담은 메뉴를 붙여서 돌려준다 (ADR-039)
// 방장이 배달앱 장바구니와 한 사람씩 맞춰보는 화면이다
public record GroupOrderItemsResponse(
        Long groupOrderId,
        String status,
        long groupOrderTotal,
        List<ParticipantItemsResponse> participants
) {

    // 방 합계는 사람별 합계를 더한다. 이미 꺼낸 값이라 쿼리가 따로 필요 없다
    public static GroupOrderItemsResponse of(GroupOrder groupOrder, List<ParticipantItemsResponse> participants) {
        return new GroupOrderItemsResponse(
                groupOrder.getId(),
                groupOrder.getStatus().name(),
                participants.stream().mapToLong(ParticipantItemsResponse::participationTotal).sum(),
                participants
        );
    }
}
