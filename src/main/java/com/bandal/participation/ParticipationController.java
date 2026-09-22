package com.bandal.participation;

import com.bandal.participation.dto.AddOrderItemRequest;
import com.bandal.participation.dto.OrderItemResponse;
import com.bandal.participation.dto.ParticipationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

// 자원의 소속을 주소가 말해준다. 참여는 방 밑에, 메뉴는 참여 밑에 (ADR-023)
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ParticipationController {

    private final ParticipationService participationService;

    @PostMapping("/group-orders/{groupOrderId}/participations")
    @ResponseStatus(HttpStatus.CREATED)
    public ParticipationResponse join(@PathVariable Long groupOrderId,
                                      @AuthenticationPrincipal Long userId) {
        return participationService.join(groupOrderId, userId);
    }

    @PostMapping("/participations/{participationId}/order-items")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderItemResponse addItem(@PathVariable Long participationId,
                                     @AuthenticationPrincipal Long userId,
                                     @Valid @RequestBody AddOrderItemRequest request) {
        return participationService.addItem(participationId, userId, request);
    }

    // 지운 뒤에는 돌려줄 게 없어서 204다
    @DeleteMapping("/order-items/{orderItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(@PathVariable Long orderItemId,
                           @AuthenticationPrincipal Long userId) {
        participationService.removeItem(orderItemId, userId);
    }
}
