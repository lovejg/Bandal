package com.bandal.common;

// 너무 자주 불렀다. 429로 나간다
// 여긴 뭉뚱그리지 않는다. 몇 번 남았는지는 숨길 정보가 아니라 알려줘야 할 정보다
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
