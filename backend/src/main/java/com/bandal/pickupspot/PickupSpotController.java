package com.bandal.pickupspot;

import com.bandal.pickupspot.dto.CreatePickupSpotRequest;
import com.bandal.pickupspot.dto.PickupSpotListResponse;
import com.bandal.pickupspot.dto.PickupSpotResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pickup-spots")
@RequiredArgsConstructor
public class PickupSpotController {

    private final PickupSpotService pickupSpotService;

    // 내 학교 거점 전부. 학교를 주소로 받으면 남의 학교 목록을 볼 수 있어서 토큰의 사용자에서 찾는다
    @GetMapping
    public PickupSpotListResponse findList(@AuthenticationPrincipal Long userId) {
        return pickupSpotService.findList(userId);
    }

    // 거점 만들기. 메일 인증 사용자만 오는 건 SecurityConfig가 확인한다(GET이 아니라서)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PickupSpotResponse create(@AuthenticationPrincipal Long userId,
                                     @Valid @RequestBody CreatePickupSpotRequest request) {
        return pickupSpotService.create(userId, request);
    }
}
