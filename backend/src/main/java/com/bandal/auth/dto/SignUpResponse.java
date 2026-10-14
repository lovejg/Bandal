package com.bandal.auth.dto;

// 가입 결과. 계정이 생겼는지는 말하지 않는다 (ADR-028)
// 이미 가입된 주소로 시도해도 똑같은 응답이 나가야 계정 존재 여부가 새지 않는다
public record SignUpResponse(String message) {
}
