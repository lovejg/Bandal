package com.bandal.settlement;

import com.bandal.settlement.dto.DeliveryFeeRequest;
import com.bandal.settlement.dto.SettlementLineResponse;
import com.bandal.settlement.dto.SettlementResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

// 정산 관련 요청. 방에 매달린 것과 정산 줄에 매달린 것이 섞여 있어서 /api를 기준으로 잡는다
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementService settlementService;

    // 배달비 입력 -> 정산중으로 전이하고 정산표를 만든다
    @PostMapping("/group-orders/{groupOrderId}/delivery-fee")
    public SettlementResponse startSettlement(@PathVariable Long groupOrderId,
                                             @AuthenticationPrincipal Long userId,
                                             @Valid @RequestBody DeliveryFeeRequest request) {
        return settlementService.startSettlement(groupOrderId, userId, request);
    }

    // 정산 정보 보기. 조회지만 계좌번호가 들어 있어서 로그인과 참여 여부를 본다
    @GetMapping("/group-orders/{groupOrderId}/settlement")
    public SettlementResponse find(@PathVariable Long groupOrderId,
                                   @AuthenticationPrincipal Long userId) {
        return settlementService.find(groupOrderId, userId);
    }

    @PostMapping("/settlements/{settlementId}/mark-paid")
    public SettlementLineResponse markPaid(@PathVariable Long settlementId,
                                           @AuthenticationPrincipal Long userId) {
        return settlementService.markPaid(settlementId, userId);
    }

    @PostMapping("/settlements/{settlementId}/confirm")
    public SettlementLineResponse confirm(@PathVariable Long settlementId,
                                          @AuthenticationPrincipal Long userId) {
        return settlementService.confirm(settlementId, userId);
    }
}
