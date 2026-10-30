package com.bandal.auth;

import com.bandal.outbox.OutboxMessage;
import com.bandal.outbox.OutboxMessageRepository;
import com.bandal.outbox.OutboxType;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

// 비밀번호를 잊은 사람의 재설정. 순서:
// 1) request: 메일 주소를 받아, 가입된 주소면 outbox에 "재설정 메일" 줄을 적는다. 응답은 가입 여부와 상관없이 같다
// 2) send: 집배원이 그 줄을 꺼내 토큰을 만들고 링크 메일을 보낸다
// 3) confirm: 링크의 토큰과 새 비밀번호를 받아 바꾸고, 모든 기기를 로그아웃시킨다
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    // 가입된 주소든 아니든 이 문장 하나로 답한다. 다르게 답하면 아무 주소나 넣어 보며 누가 가입했는지 알아낼 수 있다(가입과 같은 이유)
    public static final String REQUEST_MESSAGE = "가입된 주소라면 비밀번호 재설정 메일을 보냈습니다. 메일함을 확인해주세요";

    private final UserRepository userRepository;
    private final OutboxMessageRepository outboxMessageRepository;
    private final PasswordResetStore passwordResetStore;
    private final VerificationMailSender mailSender;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenStore refreshTokenStore;

    // 1) 재설정 요청
    // 메일을 여기서 바로 보내지 않고 outbox에 적는다. 바로 보내면 메일 서버를 기다리는 몇 초만큼 가입된 주소의 응답만 느려져
    // 시간으로 가입 여부가 샌다. outbox 줄 하나를 적는 건 몇 ms라 차이가 작다
    // 1분 안에 다시 요청하면 조용히 보내지 않는다. 429로 답하면 "가입된 주소"라는 걸 알려 주는 셈이라 응답은 똑같이 둔다
    // 탈퇴한 계정은 메일 칸이 비어 있어 찾지 못한다
    @Transactional
    public void request(String rawEmail) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) return;
        if (!passwordResetStore.tryAcquireRequestSlot(user.getId())) return;

        outboxMessageRepository.save(OutboxMessage.mail(OutboxType.PASSWORD_RESET_MAIL, user.getId(), Instant.now()));
    }

    // 2) 집배원이 부른다. 토큰은 보낼 때 만들어서 메일에만 싣는다. DB에는 남지 않는다
    public void send(Long userId, String email) {
        String token = passwordResetStore.issue(userId);
        mailSender.sendPasswordReset(email, token);
    }

    // 3) 링크를 눌러 새 비밀번호를 정했다
    // 토큰은 한 번만 쓸 수 있다(꺼내면서 지운다). 없거나 만료됐거나 이미 썼으면 모두 같은 400. 구분해 알려주면 실재했던 토큰임을 알려주는 셈이다
    // 바꾸면 모든 기기를 로그아웃시킨다(비밀번호 변경과 같은 버전 올리기). 비밀번호를 잊었다는 건 누가 가져갔을 수도 있다는 뜻이라
    // 이 기기도 자동 로그인하지 않고 새 비밀번호로 로그인하게 한다
    // 링크를 눌렀다는 건 그 메일함의 주인이라는 증명이라, 아직 메일 인증 전이면 인증도 끝낸다
    @Transactional
    public void confirm(String token, String newPassword) {
        Long userId = passwordResetStore.consume(token);
        if (userId == null) throw new IllegalArgumentException("만료되었거나 이미 사용된 링크입니다");

        User user = userRepository.findByIdOrThrow(userId);
        // 메일을 받은 뒤 탈퇴했으면 바꿀 계정이 없다
        if (user.isDeleted()) throw new IllegalArgumentException("만료되었거나 이미 사용된 링크입니다");

        user.changePassword(passwordEncoder.encode(newPassword));
        user.verifyEmail(Instant.now());
        refreshTokenStore.revokeAll(userId);
    }
}
