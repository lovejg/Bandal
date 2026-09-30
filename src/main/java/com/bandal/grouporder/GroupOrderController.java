package com.bandal.grouporder;

import com.bandal.grouporder.dto.CreateGroupOrderRequest;
import com.bandal.grouporder.dto.GroupOrderListResponse;
import com.bandal.grouporder.dto.GroupOrderResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    // 목록. 내 대학 방만 보여줘야 해서 요청자 id가 필요하다. 대학을 주소로 받으면 남의 대학 방을 볼 수 있다
    // page, size가 주소에 없으면 기본값을 쓴다. /api/group-orders 만 불러도 첫 페이지 30개가 나온다
    @GetMapping
    public GroupOrderListResponse findList(@AuthenticationPrincipal Long userId,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "30") int size) {
        return groupOrderService.findList(userId, page, size);
    }

    // 단건. 로그인은 필터가 확인하고(ADR-034), 누가 보든 같은 내용이라 요청자 id는 받지 않는다
    @GetMapping("/{groupOrderId}")
    public GroupOrderResponse find(@PathVariable Long groupOrderId) {
        return groupOrderService.find(groupOrderId);
    }
}
