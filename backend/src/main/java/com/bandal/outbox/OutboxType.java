package com.bandal.outbox;

// outbox 줄이 무슨 일인지. 이름 그대로 DB에 저장한다
public enum OutboxType {
    // 새로 가입한 사람에게 인증 링크 메일
    VERIFICATION_MAIL,
    // 이미 계정이 있는 주소로 가입하려 했다는 안내 메일. user_id는 이미 있는 계정이다
    ALREADY_REGISTERED_MAIL
}
