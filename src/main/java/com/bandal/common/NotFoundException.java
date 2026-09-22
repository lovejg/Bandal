package com.bandal.common;

// 없는 방, 없는 참여, 없는 메뉴를 가리켰을 때. 404로 나간다
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
