package com.bandal.settlement;

import com.bandal.settlement.dto.DeliveryFeeRequest;
import com.bandal.settlement.dto.SettlementResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// 정산 API. 주소가 방 아래(/api/group-orders/{id}/...)와 정산 줄 아래(/api/settlements/{id}/...) 두 갈래라
// 클래스에 공통 경로를 두지 않고 메서드마다 전체 경로를 쓴다
@RestController
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementService settlementService;

    // 방장의 배달비 입력. 방이 정산중으로 넘어가고 정산표가 생긴다 (ADR-042)
    // 방의 상태를 바꾸는 동작이라 방 주소 아래에 둔다. 나중에 재입력도 같은 주소로 받아서 201이 아니라 200이다
    @PostMapping("/api/group-orders/{groupOrderId}/delivery-fee")
    public SettlementResponse start(@PathVariable Long groupOrderId,
                                    @AuthenticationPrincipal Long userId,
                                    @Valid @RequestBody DeliveryFeeRequest request) {
        return settlementService.start(groupOrderId, userId, request);
    }
}
