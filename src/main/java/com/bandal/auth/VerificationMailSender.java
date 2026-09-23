package com.bandal.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

// 인증 메일을 만들어 보낸다. 로컬에서는 Mailpit(localhost:1025)이 받는다
@Component
@RequiredArgsConstructor
public class VerificationMailSender {

    private final JavaMailSender mailSender;

    @Value("${app.verify-link-base}")
    private String verifyLinkBase;

    @Value("${app.mail-from}")
    private String from;

    // 가입했거나 재전송을 눌렀을 때
    public void sendVerification(String email, String token) {
        String link = verifyLinkBase + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        send(email, "[반달] 이메일 인증을 완료해주세요", """
                반달에 가입해주셔서 고맙습니다.
                아래 링크를 눌러 이메일 인증을 완료해주세요.

                %s

                이 링크는 30분 뒤에 만료됩니다.
                본인이 가입한 적이 없다면 이 메일은 무시하셔도 됩니다.
                """.formatted(link));
    }

    // 이미 가입된 주소로 또 가입을 시도했을 때 (ADR-028)
    // 가입 API 응답으로는 알려주지 않고, 주소의 주인에게만 알린다
    public void sendAlreadyRegistered(String email) {
        send(email, "[반달] 이미 가입된 계정입니다", """
                이 주소로 가입을 시도한 기록이 있습니다.
                이미 계정이 있으니 로그인해주세요.

                본인이 아니라면 누군가 이 주소로 가입을 시도한 것입니다.
                계정은 안전하며, 따로 하실 일은 없습니다.
                """);
    }

    private void send(String to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        mailSender.send(message);
    }
}
