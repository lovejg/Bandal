package com.bandal.settlement.dto;

import com.bandal.grouporder.GroupOrder;
import com.bandal.settlement.Settlement;

import java.util.List;

// 방 하나의 정산 정보.
// 방장이 보면 lines에 전원이 들어오고, 참여자가 보면 자기 줄 하나만 들어온다
public record SettlementResponse(
        Long groupOrderId,
        String status,
        long deliveryFee,
        // 방 전체 메뉴 합계. 모든 줄의 totalAmount 합이 이 값 + 배달비와 같아야 한다
        long menuTotalAmount,
        HostAccountResponse hostAccount,
        List<SettlementLineResponse> lines
) {

    // lines는 보는 사람에게 보여줄 줄만 넘긴다. menuTotalAmount는 보는 사람과 상관없이 방 전체 값이다
    // 정산중부터만 만들어지는 응답이라 deliveryFee가 null일 일은 없다
    public static SettlementResponse of(GroupOrder groupOrder, long menuTotalAmount, List<Settlement> lines) {
        return new SettlementResponse(
                groupOrder.getId(),
                groupOrder.getStatus().name(),
                groupOrder.getDeliveryFee(),
                menuTotalAmount,
                HostAccountResponse.of(groupOrder.getHost()),
                lines.stream().map(SettlementLineResponse::of).toList()
        );
    }
}
