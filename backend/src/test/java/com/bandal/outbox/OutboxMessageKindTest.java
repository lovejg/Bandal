package com.bandal.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 줄 종류마다 꼭 있어야 하는 값이 있는가. DB 없이 만드는 메서드만 본다
class OutboxMessageKindTest {

    static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    static final String PAYLOAD = "{\"groupOrderId\":3,\"to\":\"CLOSED\"}";

    @Nested
    @DisplayName("메일 줄")
    class Mail {

        @Test
        @DisplayName("받을 사용자만 있고 방 id와 내용은 없다. 대기로 시작해 바로 보낼 차례다")
        void created() {
            OutboxMessage message = OutboxMessage.mail(OutboxType.VERIFICATION_MAIL, 7L, NOW);

            assertThat(message.getType()).isEqualTo(OutboxType.VERIFICATION_MAIL);
            assertThat(message.getUserId()).isEqualTo(7L);
            assertThat(message.getGroupOrderId()).isNull();
            assertThat(message.getPayload()).isNull();
            assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(message.getAttempts()).isZero();
            assertThat(message.getNextAttemptAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("받을 사용자가 없으면 만들 수 없다")
        void requiresUser() {
            assertThatThrownBy(() -> OutboxMessage.mail(OutboxType.VERIFICATION_MAIL, null, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("방 상태 종류로는 메일 줄을 만들 수 없다")
        void rejectsGroupOrderType() {
            assertThatThrownBy(() -> OutboxMessage.mail(OutboxType.GROUP_ORDER_STATUS_CHANGED, 7L, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("방 상태 줄")
    class GroupOrderStatus {

        @Test
        @DisplayName("방 id와 내용이 있고 받을 사용자는 없다. 대기로 시작해 바로 보낼 차례다")
        void created() {
            OutboxMessage message = OutboxMessage.groupOrderStatus(3L, PAYLOAD, NOW);

            assertThat(message.getType()).isEqualTo(OutboxType.GROUP_ORDER_STATUS_CHANGED);
            assertThat(message.getGroupOrderId()).isEqualTo(3L);
            assertThat(message.getPayload()).isEqualTo(PAYLOAD);
            assertThat(message.getUserId()).isNull();
            assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(message.getAttempts()).isZero();
            assertThat(message.getNextAttemptAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("방 id가 없으면 만들 수 없다")
        void requiresGroupOrder() {
            assertThatThrownBy(() -> OutboxMessage.groupOrderStatus(null, PAYLOAD, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("내용이 없거나 비었으면 만들 수 없다")
        void requiresPayload() {
            assertThatThrownBy(() -> OutboxMessage.groupOrderStatus(3L, null, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> OutboxMessage.groupOrderStatus(3L, " ", NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
