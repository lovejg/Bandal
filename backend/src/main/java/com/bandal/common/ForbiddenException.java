package com.bandal.common;

// 누구인지는 아는데 이 자원에 이 일을 할 사람이 아닐 때. 403으로 나간다 (ADR-050)
// 방장만 할 수 있는 일을 참여자가 하거나, 남의 줄이나 메뉴를 건드리는 경우
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
