package com.bandal.grouporder;

import com.bandal.grouporder.dto.CreateGroupOrderRequest;
import com.bandal.grouporder.dto.GroupOrderResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/group-orders")
@RequiredArgsConstructor
public class GroupOrderController {

    private final GroupOrderService groupOrderService;

    // X-User-Id는 로그인이 없는 동안 쓰는 임시 헤더다. 인증이 들어오면 이 자리만 바꾸면 된다
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GroupOrderResponse create(@RequestHeader("X-User-Id") Long userId,
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
                                    @RequestHeader("X-User-Id") Long userId) {
        return groupOrderService.close(groupOrderId, userId);
    }
}
