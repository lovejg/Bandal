package com.bandal.participation;

import com.bandal.participation.dto.ParticipationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
}
