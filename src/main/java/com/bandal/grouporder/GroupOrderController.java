package com.bandal.grouporder;

import com.bandal.grouporder.dto.CreateGroupOrderRequest;
import com.bandal.grouporder.dto.GroupOrderResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/group-orders")
@RequiredArgsConstructor
public class GroupOrderController {

    private final GroupOrderService groupOrderService;

    // 요청자 id는 토큰에서 나온다. 클라이언트가 정할 수 없다 (ADR-024)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GroupOrderResponse create(@AuthenticationPrincipal Long userId,
                                     @Valid @RequestBody CreateGroupOrderRequest request) {
        return groupOrderService.create(userId, request);
    }

    @GetMapping("/{groupOrderId}")
    public GroupOrderResponse find(@PathVariable Long groupOrderId) {
        return groupOrderService.find(groupOrderId);
    }

    // 상태 전이라서 자원이 아니라 동사다
    @PostMapping("/{groupOrderId}/close")
    public GroupOrderResponse close(@PathVariable Long groupOrderId,
                                    @AuthenticationPrincipal Long userId) {
        return groupOrderService.close(groupOrderId, userId);
    }
}
