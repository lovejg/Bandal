package com.bandal.auth;

// 가입 트랜잭션이 커밋된 뒤에 할 일을 알리는 사건들.
// 메일 발송은 되돌릴 수 없어서 DB 작업과 같은 트랜잭션에 둘 수 없다 (ADR-027)
public final class SignUpEvents {

    private SignUpEvents() {
    }

    // 새 계정이 만들어졌다. 인증 메일을 보내야 한다
    public record UserRegistered(Long userId, String email) {
    }

    // 이미 있는 주소로 가입을 시도했다. 계정은 만들지 않고 주인에게만 알린다 (ADR-028)
    public record DuplicateSignUp(String email) {
    }
}
