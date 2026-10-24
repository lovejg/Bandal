package com.bandal.outbox;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 집배원이 부르는 트랜잭션 셋. 메일은 보내지 않고 DB에 남는 것만 본다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "management.health.mail.enabled=false",
        "app.outbox.batch-size=2",
        "app.outbox.retry-base-seconds=30"
})
class OutboxServiceTest {

    static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    @Autowired
    OutboxService outboxService;

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
    }

    User user(String email, String nickname) {
        return userRepository.save(new User(hankuk, email, "hashed-password", nickname));
    }

    OutboxMessage queued(OutboxType type, User user, Instant at) {
        return outboxMessageRepository.save(new OutboxMessage(type, user.getId(), at));
    }

    OutboxMessage reload(OutboxMessage message) {
        return outboxMessageRepository.findById(message.getId()).orElseThrow();
    }

    @Test
    @DisplayName("찜하면 보낼 사람의 이메일과 인증 여부를 돌려주고, DB의 줄은 시도 1번에 5분 뒤로 미뤄진다")
    void claimReturnsWhatToSendAndLeases() {
        User lee = user("lee@hankuk.ac.kr", "마라탕러버");
        OutboxMessage message = queued(OutboxType.VERIFICATION_MAIL, lee, NOW.minusSeconds(10));

        List<OutboxService.Claimed> claimed = outboxService.claim(NOW);

        assertThat(claimed).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo(message.getId());
            assertThat(c.type()).isEqualTo(OutboxType.VERIFICATION_MAIL);
            assertThat(c.userId()).isEqualTo(lee.getId());
            assertThat(c.email()).isEqualTo("lee@hankuk.ac.kr");
            assertThat(c.verified()).isFalse();
        });
        // save를 부르지 않아도 변경 감지로 저장돼야 한다
        OutboxMessage saved = reload(message);
        assertThat(saved.getAttempts()).isEqualTo(1);
        assertThat(saved.getNextAttemptAt()).isEqualTo(NOW.plus(OutboxMessage.CLAIM_LEASE));
    }

    @Test
    @DisplayName("이미 인증한 사람이면 verified가 true로 온다")
    void claimTellsVerified() {
        User lee = user("lee@hankuk.ac.kr", "마라탕러버");
        lee.verifyEmail(NOW.minusSeconds(5));
        userRepository.save(lee);
        queued(OutboxType.VERIFICATION_MAIL, lee, NOW.minusSeconds(10));

        assertThat(outboxService.claim(NOW)).singleElement()
                .satisfies(c -> assertThat(c.verified()).isTrue());
    }

    @Test
    @DisplayName("한 번에 batch-size개까지만 찜하고, 찜한 줄은 바로 다시 찜되지 않는다")
    void claimsUpToBatchSizeOnce() {
        for (int i = 0; i < 3; i++) {
            queued(OutboxType.VERIFICATION_MAIL, user("u" + i + "@hankuk.ac.kr", "닉네임" + i), NOW.minusSeconds(30 - i));
        }

        assertThat(outboxService.claim(NOW)).hasSize(2);
        // 남은 하나만 나온다. 앞의 둘은 5분 뒤로 미뤄졌다
        assertThat(outboxService.claim(NOW)).hasSize(1);
        assertThat(outboxService.claim(NOW)).isEmpty();
    }

    @Test
    @DisplayName("보냄 기록은 id로 다시 읽어 저장된다")
    void markSentPersists() {
        OutboxMessage message = queued(OutboxType.VERIFICATION_MAIL, user("lee@hankuk.ac.kr", "마라탕러버"), NOW);
        outboxService.claim(NOW);

        outboxService.markSent(message.getId(), NOW.plusSeconds(1));

        OutboxMessage saved = reload(message);
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(saved.getSentAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    @DisplayName("실패 기록은 설정의 기본 간격(30초)으로 미뤄진다")
    void markFailedUsesConfiguredBase() {
        OutboxMessage message = queued(OutboxType.VERIFICATION_MAIL, user("lee@hankuk.ac.kr", "마라탕러버"), NOW);
        outboxService.claim(NOW);

        outboxService.markFailed(message.getId(), NOW);

        OutboxMessage saved = reload(message);
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
    }
}
