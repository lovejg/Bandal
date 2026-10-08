package com.bandal.settlement;

import com.bandal.settlement.dto.DeliveryFeeRequest;
import com.bandal.settlement.dto.SettlementResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// 정산 API. 주소가 방 아래(/api/group-orders/{id}/...)와 정산 줄 아래(/api/settlements/{id}/...) 두 갈래라
// 클래스에 공통 경로를 두지 않고 메서드마다 전체 경로를 쓴다
@RestController
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementService settlementService;

    // 방장의 배달비 입력. 방이 정산중으로 넘어가고 정산표가 생긴다 (ADR-042)
    // 방의 상태를 바꾸는 동작이라 방 주소 아래에 둔다. 응답은 재입력(PUT)과 같은 모양이라 200이다
    @PostMapping("/api/group-orders/{groupOrderId}/delivery-fee")
    public SettlementResponse start(@PathVariable Long groupOrderId,
                                    @AuthenticationPrincipal Long userId,
                                    @Valid @RequestBody DeliveryFeeRequest request) {
        return settlementService.start(groupOrderId, userId, request);
    }

    // 방장의 배달비 재입력. 아무도 입금에 손대지 않았을 때만 정산표를 다시 만든다 (ADR-046)
    // 첫 입력은 POST, 이미 있는 값을 바꾸는 건 PUT으로 나눴다. 같은 값을 두 번 보내도 결과가 같다
    @PutMapping("/api/group-orders/{groupOrderId}/delivery-fee")
    public SettlementResponse changeDeliveryFee(@PathVariable Long groupOrderId,
                                                @AuthenticationPrincipal Long userId,
                                                @Valid @RequestBody DeliveryFeeRequest request) {
        return settlementService.changeDeliveryFee(groupOrderId, userId, request);
    }

    // 정산 정보 조회. 방장은 전원의 줄, 참여자는 자기 줄을 받는다 (ADR-043)
    // 방 하나에 정산표는 하나라 id 없이 방 주소 아래에 단수형으로 둔다
    @GetMapping("/api/group-orders/{groupOrderId}/settlement")
    public SettlementResponse find(@PathVariable Long groupOrderId,
                                   @AuthenticationPrincipal Long userId) {
        return settlementService.find(groupOrderId, userId);
    }

    // 참여자의 "보냈어요". 정산 줄 하나의 상태를 바꾸므로 정산 줄 주소 아래에 둔다 (ADR-044)
    // 응답은 조회와 같다. 누른 사람 시점의 정산 정보가 나간다
    @PostMapping("/api/settlements/{settlementId}/mark-paid")
    public SettlementResponse markPaid(@PathVariable Long settlementId,
                                       @AuthenticationPrincipal Long userId) {
        return settlementService.markPaid(settlementId, userId);
    }

    // 방장의 "받았어요". 응답에 전원의 줄이 들어 있어 남은 사람을 바로 볼 수 있다 (ADR-044)
    @PostMapping("/api/settlements/{settlementId}/confirm")
    public SettlementResponse confirm(@PathVariable Long settlementId,
                                      @AuthenticationPrincipal Long userId) {
        return settlementService.confirm(settlementId, userId);
    }

    // 방장의 확인 취소. 확인만 되돌리고 취소한 흔적을 남긴다 (ADR-045)
    // 지우는 건 자원이 아니라 상태라서 DELETE가 아니라 동사 주소다 (ADR-023)
    @PostMapping("/api/settlements/{settlementId}/unconfirm")
    public SettlementResponse revokeConfirm(@PathVariable Long settlementId,
                                            @AuthenticationPrincipal Long userId) {
        return settlementService.revokeConfirm(settlementId, userId);
    }
}
