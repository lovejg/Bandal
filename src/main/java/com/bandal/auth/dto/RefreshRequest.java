package com.bandal.auth.dto;

import jakarta.validation.constraints.NotBlank;

// 갱신과 로그아웃이 같은 본문을 쓴다
public record RefreshRequest(

        @NotBlank(message = "리프레시 토큰이 필요합니다")
        String refreshToken
) {
}
