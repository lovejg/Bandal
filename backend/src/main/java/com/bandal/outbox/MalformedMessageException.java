package com.bandal.outbox;

// Kafka 글 자체가 잘못됐을 때(헤더 eventId가 없거나 숫자가 아님). 다시 읽어도 똑같이 잘못돼 있다
// 컨슈머 실패 처리에서 "다시 하지 않고 바로 DLT로" 보낼 예외로 쓴다
public class MalformedMessageException extends RuntimeException {

    public MalformedMessageException(String message) {
        super(message);
    }
}
