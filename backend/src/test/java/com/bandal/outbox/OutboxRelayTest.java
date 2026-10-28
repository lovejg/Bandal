package com.bandal.outbox;

import com.bandal.TestMails;
import com.bandal.TestMails.SentMail;
import com.bandal.TestcontainersConfiguration;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.settlement.SettlementRepository;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 집배원 한 바퀴. 스케줄러는 꺼 두고 runAt을 직접 부른다. 메일 서버는 가짜로 바꾼다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "management.health.mail.enabled=false",
        "app.outbox.retry-base-seconds=30"
})
class OutboxRelayTest {

    static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    @Autowired
    OutboxRelay outboxRelay;

    @Autowired
    OutboxMessageRepository outboxMessageRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    UniversityRepository universityRepository;

    @Autowired
    SettlementRepository settlementRepository;

    @Autowired
    OrderItemRepository orderItemRepository;

    @Autowired
    ParticipationRepository participationRepository;

    @Autowired
    GroupOrderRepository groupOrderRepository;

    @Autowired
    PickupSpotRepository pickupSpotRepository;

    @MockitoBean
    JavaMailSender mailSender;

    University hankuk;

    @BeforeEach
    void setUp() {
        outboxMessageRepository.deleteAll();
        settlementRepository.deleteAll();
        orderItemRepository.deleteAll();
        participationRepository.deleteAll();
        groupOrderRepository.deleteAll();
        userRepository.deleteAll();
        pickupSpotRepository.deleteAll();
        universityRepository.deleteAll();

        hankuk = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        TestMails.stub(mailSender);
    }

    User user(String email, String nickname) {
        return userRepository.save(new User(hankuk, email, "hashed-password", nickname));
    }

    OutboxMessage queued(OutboxType type, User user) {
        return outboxMessageRepository.save(OutboxMessage.mail(type, user.getId(), NOW.minusSeconds(10)));
    }

    OutboxMessage reload(OutboxMessage message) {
        return outboxMessageRepository.findById(message.getId()).orElseThrow();
    }

    SentMail sentMail() {
        return TestMails.only(mailSender);
    }

    @Test
    @DisplayName("인증 메일 줄은 토큰 링크를 담아 보내고 보냄으로 적는다")
    void sendsVerificationMail() {
        OutboxMessage message = queued(OutboxType.VERIFICATION_MAIL, user("lee@hankuk.ac.kr", "마라탕러버"));

        outboxRelay.runAt(NOW);

        SentMail mail = sentMail();
        assertThat(mail.getTo()).containsExactly("lee@hankuk.ac.kr");
        assertThat(mail.getText()).contains("token=");
        // 글자 본문만 있으면 주소를 링크로 바꿔 주지 않는 메일 앱에서 눌리지 않는다. HTML 본문에 진짜 링크(a href)가 있어야 한다
        assertThat(mail.getHtml()).contains("<a href=\"http://localhost:8080/api/auth/verify?token=");
        assertThat(reload(message).getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(reload(message).getSentAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("이미 가입 안내 줄은 안내 메일을 보내고 보냄으로 적는다")
    void sendsAlreadyRegisteredMail() {
        OutboxMessage message = queued(OutboxType.ALREADY_REGISTERED_MAIL, user("lee@hankuk.ac.kr", "마라탕러버"));

        outboxRelay.runAt(NOW);

        SentMail mail = sentMail();
        assertThat(mail.getTo()).containsExactly("lee@hankuk.ac.kr");
        assertThat(mail.getSubject()).contains("이미 가입된");
        // 로그인 버튼은 사이트 첫 화면으로 간다
        assertThat(mail.getHtml()).contains("<a href=\"http://localhost:8080/\"");
        assertThat(reload(message).getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("기다리는 사이에 이미 인증했으면 인증 메일을 보내지 않고 보냄으로 적는다")
    void skipsVerifiedUser() {
        User lee = user("lee@hankuk.ac.kr", "마라탕러버");
        lee.verifyEmail(NOW.minusSeconds(5));
        userRepository.save(lee);
        OutboxMessage message = queued(OutboxType.VERIFICATION_MAIL, lee);

        outboxRelay.runAt(NOW);

        verify(mailSender, never()).send(any(MimeMessage.class));
        assertThat(reload(message).getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("한 통이 실패하면 그 줄만 30초 뒤로 미루고, 다음 줄은 그대로 보낸다")
    void failureOnlyDelaysThatRow() {
        OutboxMessage broken = queued(OutboxType.VERIFICATION_MAIL, user("broken@hankuk.ac.kr", "닉네임1"));
        OutboxMessage fine = queued(OutboxType.VERIFICATION_MAIL, user("fine@hankuk.ac.kr", "닉네임2"));
        doThrow(new MailSendException("메일 서버가 멈췄다"))
                .when(mailSender).send(argThat((MimeMessage m) -> TestMails.recipient(m).equals("broken@hankuk.ac.kr")));

        outboxRelay.runAt(NOW);

        verify(mailSender, times(2)).send(any(MimeMessage.class));
        OutboxMessage failed = reload(broken);
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(reload(fine).getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("다섯 번째 실패에서 실패로 끝나고, 더는 찜되지 않는다")
    void givesUpAfterMaxAttempts() {
        OutboxMessage message = queued(OutboxType.VERIFICATION_MAIL, user("lee@hankuk.ac.kr", "마라탕러버"));
        doThrow(new MailSendException("메일 서버가 멈췄다")).when(mailSender).send(any(MimeMessage.class));

        // 매번 미뤄진 시각보다 넉넉히 뒤에 돌린다(30초, 1분, 2분, 4분)
        Instant at = NOW;
        for (int i = 0; i < OutboxMessage.MAX_ATTEMPTS; i++) {
            outboxRelay.runAt(at);
            at = at.plusSeconds(600);
        }
        assertThat(reload(message).getStatus()).isEqualTo(OutboxStatus.FAILED);

        outboxRelay.runAt(at.plusSeconds(3600));
        verify(mailSender, times(OutboxMessage.MAX_ATTEMPTS)).send(any(MimeMessage.class));
    }
}
