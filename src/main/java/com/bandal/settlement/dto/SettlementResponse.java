package com.bandal.settlement.dto;

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
}
