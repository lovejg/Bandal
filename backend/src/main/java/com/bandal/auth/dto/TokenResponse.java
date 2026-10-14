package com.bandal.auth.dto;

// 로그인과 갱신의 응답
// accessToken은 매 요청의 Authorization 헤더에 실어 보낸다
// refreshToken은 액세스 토큰이 만료됐을 때 새로 받을 때만 쓴다
public record TokenResponse(
        String accessToken,
        String refreshToken
) {
}
