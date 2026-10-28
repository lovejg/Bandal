package com.bandal.auth;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

// 인증 메일을 만들어 보낸다. 로컬에서는 Mailpit(localhost:1025)이 받는다
// HTML과 글자만 있는 본문을 같이 넣는다. 메일 앱이 HTML을 보여 주면 버튼이, 못 보여 주면 글자 본문이 보인다
// 글자만 보내던 때는 주소를 링크로 바꿔 주지 않는 메일 앱에서 눌리지 않았다
@Component
@RequiredArgsConstructor
public class VerificationMailSender {

    private final JavaMailSender mailSender;

    @Value("${app.verify-link-base}")
    private String verifyLinkBase;

    @Value("${app.mail-from}")
    private String from;

    // 메일 문구의 만료 시간이 실제 토큰 수명과 어긋나지 않게 같은 설정을 읽는다
    @Value("${app.verification-token-minutes}")
    private long tokenMinutes;

    // 가입했거나 재전송을 눌렀을 때
    public void sendVerification(String email, String token) {
        String link = verifyLinkBase + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        String text = """
                반달에 가입해 주셔서 고맙습니다.
                아래 링크를 열어 학교 이메일 인증을 마쳐 주세요.

                %s

                이 링크는 %d분 동안만 쓸 수 있습니다.
                반달에 가입한 적이 없다면 이 메일은 무시하셔도 됩니다.
                """.formatted(link, tokenMinutes);
        String html = layout(
                "이메일 인증을 마쳐 주세요",
                "반달에 가입해 주셔서 고맙습니다.<br>아래 버튼을 누르면 학교 이메일 인증이 끝나고, 공구방을 만들거나 참여할 수 있어요.",
                "이메일 인증하기", link,
                "이 링크는 " + tokenMinutes + "분 동안만 쓸 수 있습니다. 버튼이 눌리지 않으면 아래 주소를 브라우저에 붙여 넣어 주세요.<br>"
                        + "<span style=\"word-break:break-all;color:#6e6a61\">" + link + "</span>",
                "반달에 가입한 적이 없다면 이 메일은 무시하셔도 됩니다.");
        send(email, "[반달] 이메일 인증을 완료해 주세요", text, html);
    }

    // 이미 가입된 주소로 또 가입을 시도했을 때
    // 가입 API 응답으로는 알려주지 않고, 주소의 주인에게만 알린다
    public void sendAlreadyRegistered(String email) {
        String home = URI.create(verifyLinkBase).resolve("/").toString();
        String text = """
                이 주소로 반달 가입을 시도한 기록이 있습니다.
                이미 계정이 있으니 로그인해 주세요.

                %s

                본인이 아니라면 누군가 이 주소로 가입을 시도한 것입니다.
                계정은 안전하며, 따로 하실 일은 없습니다.
                """.formatted(home);
        String html = layout(
                "이미 가입된 계정입니다",
                "이 주소로 반달 가입을 시도한 기록이 있습니다.<br>이미 계정이 있으니 로그인해 주세요.",
                "반달 로그인", home,
                null,
                "본인이 아니라면 누군가 이 주소로 가입을 시도한 것입니다. 계정은 안전하며, 따로 하실 일은 없습니다.");
        send(email, "[반달] 이미 가입된 계정입니다", text, html);
    }

    // 두 메일이 같이 쓰는 틀. 메일 앱은 style 태그와 바깥 CSS를 지우는 곳이 많아서 모든 꾸밈을 태그 안(style="")에 쓴다
    // 칸 나누기도 같은 이유로 div 대신 table로 한다. 들어가는 문구는 우리가 정한 것과 링크뿐이라 따로 이스케이프하지 않는다
    private static String layout(String title, String body, String buttonText, String buttonLink,
                                 String afterButton, String footer) {
        String after = afterButton == null ? "" : """
                <p style="margin:20px 0 0;font-size:13px;line-height:1.6;color:#6e6a61">%s</p>
                """.formatted(afterButton);
        return """
                <!doctype html>
                <html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#f6f3ec">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f6f3ec;padding:32px 16px">
                <tr><td align="center">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:480px;background:#fffdf8;border:1px solid #dcd6c8;border-radius:4px;font-family:-apple-system,BlinkMacSystemFont,'Apple SD Gothic Neo','Malgun Gothic',sans-serif;color:#1c1b19">
                <tr><td style="padding:22px 28px;border-bottom:3px solid #e8590c">
                <span style="font-size:20px;font-weight:800;letter-spacing:-0.5px">반달</span>
                <span style="font-size:13px;color:#6e6a61;margin-left:6px">같은 건물 사람들이랑 반씩</span>
                </td></tr>
                <tr><td style="padding:28px">
                <h1 style="margin:0 0 14px;font-size:20px;line-height:1.4">%s</h1>
                <p style="margin:0 0 24px;font-size:15px;line-height:1.7">%s</p>
                <a href="%s" style="display:inline-block;padding:13px 24px;border-radius:4px;background:#e8590c;color:#ffffff;font-size:15px;font-weight:700;text-decoration:none">%s</a>
                %s
                </td></tr>
                <tr><td style="padding:18px 28px;border-top:1px solid #dcd6c8;font-size:12px;line-height:1.6;color:#6e6a61">%s<br>이 메일은 보내기 전용입니다.</td></tr>
                </table>
                </td></tr>
                </table>
                </body></html>
                """.formatted(title, body, buttonLink, buttonText, after, footer);
    }

    private void send(String to, String subject, String text, String html) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            // true: 본문을 여러 조각(글자, HTML)으로 넣는다
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html);
        } catch(MessagingException e) {
            // 주소 형식이 틀린 경우 등. 보내기 실패와 같게 outbox가 재시도하고 기록한다
            throw new MailPreparationException("메일을 만들지 못했습니다", e);
        }
        mailSender.send(message);
    }
}
