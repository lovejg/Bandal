package com.bandal;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 가짜 JavaMailSender로 보낸 메일을 꺼내 보는 도우미. 메일이 HTML과 글자 두 조각이라 꺼내는 일이 길어서 한곳에 둔다
public final class TestMails {

    private TestMails() {}

    // 가짜 메일 서버는 createMimeMessage()에 null을 준다. 빈 편지를 진짜로 만들어 주게 한다. @BeforeEach에서 부른다
    public static void stub(JavaMailSender mailSender) {
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
    }

    // 보낸 메일 전부(보낸 순서)
    public static List<SentMail> all(JavaMailSender mailSender) {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, atLeastOnce()).send(captor.capture());
        return captor.getAllValues().stream().map(SentMail::of).toList();
    }

    // 딱 한 통 보냈는지 확인하고 그 메일
    public static SentMail only(JavaMailSender mailSender) {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return SentMail.of(captor.getValue());
    }

    // 받는 사람 주소. argThat에서 "이 주소로 가는 메일만 실패" 같은 조건에 쓴다
    public static String recipient(MimeMessage message) {
        try {
            return message.getRecipients(Message.RecipientType.TO)[0].toString();
        } catch(Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // 꺼낸 메일. text는 글자 본문, html은 HTML 본문
    public record SentMail(String[] to, String subject, String text, String html) {

        public String[] getTo() { return to; }
        public String getSubject() { return subject; }
        public String getText() { return text; }
        public String getHtml() { return html; }

        public static SentMail of(MimeMessage message) {
            try {
                // 머리글(Content-Type 등)을 채운다. 안 하면 조각이 어떤 종류인지 아직 비어 있다
                message.saveChanges();
                List<String> texts = new ArrayList<>();
                List<String> htmls = new ArrayList<>();
                collect(message, texts, htmls);
                String[] to = Arrays.stream(message.getRecipients(Message.RecipientType.TO)).map(Address::toString).toArray(String[]::new);
                return new SentMail(to, message.getSubject(), String.join("", texts), String.join("", htmls));
            } catch(Exception e) {
                throw new IllegalStateException("메일을 읽지 못했습니다", e);
            }
        }

        // 여러 겹(섞음 > 관련 > 대안)으로 싸인 조각을 끝까지 내려가며 글자와 HTML을 모은다
        private static void collect(Part part, List<String> texts, List<String> htmls) throws Exception {
            Object content = part.getContent();
            if(content instanceof Multipart multipart) {
                for(int i = 0; i < multipart.getCount(); i++) {
                    BodyPart child = multipart.getBodyPart(i);
                    collect(child, texts, htmls);
                }
            }
            else if(part.isMimeType("text/html")) htmls.add((String) content);
            else if(part.isMimeType("text/plain")) texts.add((String) content);
        }
    }
}
