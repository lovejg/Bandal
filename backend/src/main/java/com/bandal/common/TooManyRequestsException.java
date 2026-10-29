package com.bandal.common;

// 너무 자주 불렀다. 429로 나간다
// 로그인 실패처럼 뭉뚱그리지 않고 "너무 자주 보냈다"를 그대로 알린다. 숨길 정보가 아니라 기다리라고 알려 줘야 할 정보다
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
