package com.bandal.grouporder;

import com.bandal.grouporder.dto.CreateGroupOrderRequest;
import com.bandal.grouporder.dto.GroupOrderResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/group-orders")
@RequiredArgsConstructor
public class GroupOrderController {

    private final GroupOrderService groupOrderService;

    // 요청자 id는 토큰에서 나온다. 본문으로 받으면 남을 방장으로 만들 수 있다 (ADR-024)
    // @Valid가 형식을 먼저 거른다. 여기서 막히면 서비스까지 가지도 않는다
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GroupOrderResponse create(@AuthenticationPrincipal Long userId,
                                     @Valid @RequestBody CreateGroupOrderRequest request) {
        return groupOrderService.create(userId, request);
    }
}
