package com.bandal.common;

// 본인임을 증명하지 못했다. 401로 나간다 (ADR-026)
// 403(권한 없음)과 다르다. 401은 "누구인지 모르겠다", 403은 "누군지는 알지만 안 된다"
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
