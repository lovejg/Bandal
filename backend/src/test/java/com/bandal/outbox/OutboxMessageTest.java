package com.bandal.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// DB 없이 outbox 한 줄의 규칙만 확인한다
class OutboxMessageTest {

    static final Long USER_ID = 3_000L;
    static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    static final Duration RETRY_BASE = Duration.ofSeconds(30);

    OutboxMessage newMessage() {
        return new OutboxMessage(OutboxType.VERIFICATION_MAIL, USER_ID, NOW);
    }

    // 찜하고 실패하기를 n번 되풀이한다. 실패할 때마다 1분씩 지난 것으로 한다
    OutboxMessage failedTimes(int n) {
        OutboxMessage message = newMessage();
        Instant at = NOW;
        for (int i = 0; i < n; i++) {
            message.claim(at);
            message.markFailed(at, RETRY_BASE);
            at = at.plusSeconds(60);
        }
        return message;
    }

    @Nested
    @DisplayName("만들기")
    class Create {

        @Test
        @DisplayName("대기로 시작하고 바로 보낼 차례다")
        void startsPending() {
            OutboxMessage message = newMessage();

            assertThat(message.getType()).isEqualTo(OutboxType.VERIFICATION_MAIL);
            assertThat(message.getUserId()).isEqualTo(USER_ID);
            assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(message.getAttempts()).isZero();
            assertThat(message.getNextAttemptAt()).isEqualTo(NOW);
            assertThat(message.getCreatedAt()).isEqualTo(NOW);
            assertThat(message.getSentAt()).isNull();
        }
    }

    @Nested
    @DisplayName("찜(claim)")
    class Claim {

        @Test
        @DisplayName("시도 횟수가 하나 늘고, 5분 동안 다른 집배원이 가져가지 않게 미룬다")
        void countsAndLeases() {
            OutboxMessage message = newMessage();

            message.claim(NOW);

            assertThat(message.getAttempts()).isEqualTo(1);
            assertThat(message.getNextAttemptAt()).isEqualTo(NOW.plus(OutboxMessage.CLAIM_LEASE));
            assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
        }

        @Test
        @DisplayName("보낸 줄은 다시 찜할 수 없다")
        void rejectsSent() {
            OutboxMessage message = newMessage();
            message.claim(NOW);
            message.markSent(NOW);

            assertThatThrownBy(() -> message.claim(NOW)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("실패로 끝난 줄은 다시 찜할 수 없다")
        void rejectsFailed() {
            OutboxMessage message = failedTimes(OutboxMessage.MAX_ATTEMPTS);

            assertThatThrownBy(() -> message.claim(NOW)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("보냄(markSent)")
    class MarkSent {

        @Test
        @DisplayName("보냄으로 바뀌고 보낸 시각이 남는다")
        void marksSent() {
            OutboxMessage message = newMessage();
            message.claim(NOW);
            Instant sentAt = NOW.plusSeconds(2);

            message.markSent(sentAt);

            assertThat(message.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(message.getSentAt()).isEqualTo(sentAt);
        }

        @Test
        @DisplayName("이미 보낸 줄을 또 보냄으로 바꿀 수 없다")
        void rejectsTwice() {
            OutboxMessage message = newMessage();
            message.claim(NOW);
            message.markSent(NOW);

            assertThatThrownBy(() -> message.markSent(NOW)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("실패(markFailed)")
    class MarkFailed {

        @Test
        @DisplayName("첫 실패는 대기 그대로, 30초 뒤로 미룬다")
        void firstRetryAfterBase() {
            OutboxMessage message = newMessage();
            message.claim(NOW);

            message.markFailed(NOW, RETRY_BASE);

            assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(message.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        }

        @Test
        @DisplayName("실패할수록 간격이 두 배가 된다: 30초, 1분, 2분, 4분")
        void doublesInterval() {
            OutboxMessage message = newMessage();
            long[] expectedSeconds = {30, 60, 120, 240};

            for (long expected : expectedSeconds) {
                message.claim(NOW);
                message.markFailed(NOW, RETRY_BASE);
                assertThat(message.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(expected));
            }
            assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
        }

        @Test
        @DisplayName("다섯 번째 실패에서 실패로 끝난다")
        void failsAtMax() {
            OutboxMessage message = failedTimes(OutboxMessage.MAX_ATTEMPTS);

            assertThat(message.getAttempts()).isEqualTo(5);
            assertThat(message.getStatus()).isEqualTo(OutboxStatus.FAILED);
        }

        @Test
        @DisplayName("네 번째 실패까지는 아직 대기다")
        void stillPendingBeforeMax() {
            OutboxMessage message = failedTimes(OutboxMessage.MAX_ATTEMPTS - 1);

            assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
        }

        @Test
        @DisplayName("보낸 줄은 실패로 바꿀 수 없다")
        void rejectsSent() {
            OutboxMessage message = newMessage();
            message.claim(NOW);
            message.markSent(NOW);

            assertThatThrownBy(() -> message.markFailed(NOW, RETRY_BASE)).isInstanceOf(IllegalStateException.class);
        }
    }
}
