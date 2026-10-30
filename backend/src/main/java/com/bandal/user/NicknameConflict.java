package com.bandal.user;

import org.springframework.dao.DataIntegrityViolationException;

// 닉네임이 겹쳐 DB가 거부했는지 알아본다. 가입과 닉네임 변경이 같이 쓴다
//
// 서비스는 저장하기 전에 existsByNickname으로 먼저 묻는다. 그런데 두 요청이 동시에 오면 둘 다 "없다"는 답을 받고 둘 다 저장하러 간다.
// 그때 막아 주는 건 DB의 unique 제약(uk_users_nickname)이다. 두 번째 저장이 거부되며 DataIntegrityViolationException이 난다.
// 그대로 두면 500이 나간다(재현: 동시 요청 20회 모두 한쪽이 500). 그래서 이 예외를 잡아 "이미 쓰이는 닉네임"(409)으로 바꾼다
//
// 락으로는 못 막는다. 두 요청은 서로 다른 사용자의 줄을 쓴다(변경) 또는 새 줄을 만든다(가입). 같은 줄을 두고 다투지 않아서
// 낙관적 락(@Version)도 비관적 락(FOR UPDATE)도 걸릴 데가 없다
public final class NicknameConflict {

    public static final String MESSAGE = "이미 쓰이고 있는 닉네임입니다";

    private NicknameConflict() {
    }

    // 같은 예외가 이메일 중복(uk_users_email)으로도 날 수 있다. 제약 이름으로 닉네임 것만 골라낸다
    public static boolean isCause(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains("uk_users_nickname");
    }
}
