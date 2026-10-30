package com.bandal.outbox;

// outbox 줄이 무슨 일인지. 이름 그대로 DB에 저장한다
public enum OutboxType {
    // 새로 가입한 사람에게 인증 링크 메일
    VERIFICATION_MAIL,
    // 이미 계정이 있는 주소로 가입하려 했다는 안내 메일. user_id는 이미 있는 계정이다
    ALREADY_REGISTERED_MAIL,
    // 비밀번호를 잊은 사람에게 재설정 링크 메일. 링크 속 토큰은 보낼 때 만든다(DB에 남기지 않는다)
    PASSWORD_RESET_MAIL,
    // 방 상태가 바뀌었다. 메일이 아니라 Kafka로 보낸다. user_id 없이 group_order_id와 payload가 있다
    GROUP_ORDER_STATUS_CHANGED
}
