package com.bandal.common;

// 실패했을 때 나가는 본문. 프론트가 이 message로 안내 문구를 만든다 (ADR-023)
public record ErrorResponse(int status, String message) {
}
