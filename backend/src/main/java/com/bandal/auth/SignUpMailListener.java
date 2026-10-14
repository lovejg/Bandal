package com.bandal.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 가입 메일을 보낸다. 가입 트랜잭션이 커밋된 뒤에만 실행된다 (ADR-027)
//
// @TransactionalEventListener는 사건을 바로 처리하지 않고 트랜잭션이 끝나기를 기다린다.
// AFTER_COMMIT이면 커밋에 성공했을 때만 실행되고, 롤백되면 아예 실행되지 않는다.
// 그래서 "없는 계정의 인증 메일이 나가는" 일이 생기지 않는다.
@Component
@RequiredArgsConstructor
@Slf4j
public class SignUpMailListener {

    private final EmailVerificationService emailVerificationService;
    private final VerificationMailSender mailSender;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserRegistered(SignUpEvents.UserRegistered event) {
        // 여기서 던지면 커밋을 끝낸 호출자에게 예외가 올라가 500이 나간다.
        // 계정은 이미 만들어졌으니 가입은 성공으로 두고, 못 보낸 건 로그로 남긴다.
        // 사용자는 재전송으로 다시 받을 수 있다
        try {
            emailVerificationService.send(event.userId(), event.email());
        } catch (Exception e) {
            log.error("인증 메일을 보내지 못했다. userId={}", event.userId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDuplicateSignUp(SignUpEvents.DuplicateSignUp event) {
        try {
            mailSender.sendAlreadyRegistered(event.email());
        } catch (Exception e) {
            // 주소를 로그에 남기지 않는다. 로그도 개인정보가 새는 경로다
            log.error("이미 가입된 계정 안내 메일을 보내지 못했다", e);
        }
    }
}
