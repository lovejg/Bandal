package com.bandal.auth;

import com.bandal.common.NotFoundException;
import com.bandal.common.TooManyRequestsException;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

// 이메일 인증의 흐름을 맡는다. 토큰 저장은 EmailVerificationStore, 발송은 VerificationMailSender (ADR-027)
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final EmailVerificationStore verificationStore;
    private final VerificationMailSender mailSender;
    private final UserRepository userRepository;

    @Value("${app.resend-limit}")
    private long resendLimit;

    // 토큰을 발급해서 인증 메일을 보낸다. 가입 직후와 재전송이 같이 쓴다
    public void send(Long userId, String email) {
        String token = verificationStore.issue(userId);
        mailSender.sendVerification(email, token);
    }

    // 사용자가 "메일 다시 보내기"를 눌렀다. 로그인은 되어 있다
    @Transactional(readOnly = true)
    public void resend(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다"));
        if(user.isEmailVerified()) throw new IllegalStateException("이미 인증된 계정입니다");

        if(verificationStore.increaseResendCount(userId) > resendLimit) {
            throw new TooManyRequestsException("메일을 너무 자주 보냈습니다. 잠시 뒤에 다시 시도해주세요");
        }
        else {
            send(userId, user.getEmail());
        }
    }

    // 메일 속 링크를 눌렀다. 토큰을 쓰고 사용자를 인증 완료로 바꾼다
    @Transactional
    public void verify(String token) {
        Long userId = verificationStore.consume(token);
        if(userId == null) throw new IllegalArgumentException("만료되었거나 이미 사용된 링크입니다");
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다"));
        user.verifyEmail(Instant.now());
    }
}
