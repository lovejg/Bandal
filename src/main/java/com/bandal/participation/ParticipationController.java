package com.bandal.participation;

import com.bandal.participation.dto.OrderItemRequest;
import com.bandal.participation.dto.ParticipationItemsResponse;
import com.bandal.participation.dto.ParticipationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ParticipationController {

    private final ParticipationService participationService;

    // 방 참여. "이 방의 참여 목록에 하나를 추가한다"로 읽는다
    // 방은 주소에서, 사용자는 토큰에서 온다. 그래서 본문이 없다
    @PostMapping("/api/group-orders/{groupOrderId}/participations")
    @ResponseStatus(HttpStatus.CREATED)
    public ParticipationResponse join(@PathVariable Long groupOrderId,
                                      @AuthenticationPrincipal Long userId) {
        return participationService.join(groupOrderId, userId);
    }

    // 방 나가기. 지우는 대상이 참여 행이라 참여 id를 받는다 (ADR-037)
    // 돌려줄 게 없어서 204(성공, 본문 없음)
    @DeleteMapping("/api/participations/{participationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable Long participationId,
                      @AuthenticationPrincipal Long userId) {
        participationService.leave(participationId, userId);
    }

    // 메뉴 담기. "이 참여의 메뉴 목록에 한 줄을 추가한다" (ADR-038)
    // @Valid가 DTO의 @NotBlank, @Max 같은 검사를 돌린다. 걸리면 서비스까지 안 가고 400
    @PostMapping("/api/participations/{participationId}/order-items")
    @ResponseStatus(HttpStatus.CREATED)
    public ParticipationItemsResponse addItem(@PathVariable Long participationId,
                                              @AuthenticationPrincipal Long userId,
                                              @Valid @RequestBody OrderItemRequest request) {
        return participationService.addItem(participationId, userId, request);
    }

    // 메뉴 고치기. 폼 전체를 다시 받아 통째로 바꾸니 PUT
    @PutMapping("/api/order-items/{orderItemId}")
    public ParticipationItemsResponse updateItem(@PathVariable Long orderItemId,
                                                 @AuthenticationPrincipal Long userId,
                                                 @Valid @RequestBody OrderItemRequest request) {
        return participationService.updateItem(orderItemId, userId, request);
    }

    // 메뉴 빼기. 화면에 남아 있으니 204가 아니라 다시 그릴 값을 200으로 준다
    @DeleteMapping("/api/order-items/{orderItemId}")
    public ParticipationItemsResponse removeItem(@PathVariable Long orderItemId,
                                                 @AuthenticationPrincipal Long userId) {
        return participationService.removeItem(orderItemId, userId);
    }
}
