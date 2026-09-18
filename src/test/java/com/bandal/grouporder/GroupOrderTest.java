package com.bandal.grouporder;

import com.bandal.pickupspot.PickupSpot;
import com.bandal.university.University;
import com.bandal.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// DB 없이 GroupOrder의 전이 규칙만 확인한다.
class GroupOrderTest {

    // 운영 DB처럼 작지 않은 id를 쓴다
    static final Long HOST_ID = 1_000L;
    static final Long OTHER_ID = 1_001L;
    static final long MIN_ORDER_AMOUNT = 15_000;
    static final Instant DEADLINE = Instant.parse("2026-09-17T10:30:00Z");

    GroupOrder groupOrder;

    @BeforeEach
    void setUp() {
        University university = new University("한국대학교", "hankuk.ac.kr");
        PickupSpot pickupSpot = new PickupSpot(university, "제1기숙사 로비", null);
        User host = new User(university, "kim@hankuk.ac.kr", "hashed-password", "배고파");
        // 저장하지 않은 엔티티라 id가 null이다. 방장 확인을 테스트하려고 id만 직접 넣는다.
        // 실제로는 엔티티의 id(DB에서 읽음)와 요청자 id(요청에서 읽음)가 서로 다른 Long 객체라서,
        // HOST_ID를 그대로 넣지 않고 값만 같은 새 Long을 넣는다.
        ReflectionTestUtils.setField(host, "id", Long.valueOf(HOST_ID.longValue()));

        groupOrder = new GroupOrder(host, pickupSpot, "○○마라탕", MIN_ORDER_AMOUNT, DEADLINE, 4);
    }

    @Nested
    @DisplayName("방장의 수동 마감")
    class CloseByHost {

        @Test
        @DisplayName("2명 이상이고 메뉴 합계가 최소주문금액 이상이면 마감된다")
        void closes() {
            groupOrder.closeByHost(HOST_ID, 3, 20_000);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CLOSED);
        }

        @Test
        @DisplayName("딱 2명, 딱 최소주문금액이어도 마감된다")
        void closesAtBoundary() {
            groupOrder.closeByHost(HOST_ID, 2, MIN_ORDER_AMOUNT);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CLOSED);
        }

        @Test
        @DisplayName("방장이 아닌 사람은 마감할 수 없다")
        void rejectsNonHost() {
            assertThatThrownBy(() -> groupOrder.closeByHost(OTHER_ID, 3, 20_000))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.RECRUITING);
        }

        @Test
        @DisplayName("방장 혼자면 마감할 수 없고 모집중으로 남는다")
        void rejectsSinglePerson() {
            assertThatThrownBy(() -> groupOrder.closeByHost(HOST_ID, 1, 20_000))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.RECRUITING);
        }

        @Test
        @DisplayName("최소주문금액에 1원이라도 모자라면 마감할 수 없고, 취소되지도 않는다")
        void rejectsShortAmount() {
            assertThatThrownBy(() -> groupOrder.closeByHost(HOST_ID, 3, MIN_ORDER_AMOUNT - 1))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.RECRUITING);
            assertThat(groupOrder.getCancelReason()).isNull();
            assertThat(groupOrder.getCancelType()).isNull();
        }

        @Test
        @DisplayName("이미 마감된 방은 다시 마감할 수 없다")
        void rejectsWhenNotRecruiting() {
            groupOrder.closeByHost(HOST_ID, 3, 20_000);

            assertThatThrownBy(() -> groupOrder.closeByHost(HOST_ID, 3, 20_000))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("마감 시각 도달")
    class CloseAtDeadline {

        @Test
        @DisplayName("2명 이상이고 메뉴 합계가 최소주문금액 이상이면 마감되고 취소 사유는 없다")
        void closes() {
            groupOrder.closeAtDeadline(3, 20_000, DEADLINE.plusSeconds(1));

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CLOSED);
            assertThat(groupOrder.getCancelReason()).isNull();
            assertThat(groupOrder.getCancelType()).isNull();
        }

        @Test
        @DisplayName("정확히 마감 시각이면 처리된다")
        void closesExactlyAtDeadline() {
            groupOrder.closeAtDeadline(3, 20_000, DEADLINE);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CLOSED);
        }

        @Test
        @DisplayName("마감 1초 전에는 처리할 수 없고 모집중으로 남는다")
        void rejectsBeforeDeadline() {
            assertThatThrownBy(() -> groupOrder.closeAtDeadline(3, 20_000, DEADLINE.minusSeconds(1)))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.RECRUITING);
        }

        @Test
        @DisplayName("방장 혼자면 취소되고 사유가 남는다")
        void cancelsWhenSinglePerson() {
            groupOrder.closeAtDeadline(1, 20_000, DEADLINE);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CANCELED);
            assertThat(groupOrder.getCancelReason()).isNotBlank();
            assertThat(groupOrder.getCancelType()).isEqualTo(CancelType.DEADLINE_UNMET);
        }

        @Test
        @DisplayName("최소주문금액에 모자라면 취소되고 사유가 남는다")
        void cancelsWhenShortAmount() {
            groupOrder.closeAtDeadline(3, MIN_ORDER_AMOUNT - 1, DEADLINE);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CANCELED);
            assertThat(groupOrder.getCancelReason()).isNotBlank();
            assertThat(groupOrder.getCancelType()).isEqualTo(CancelType.DEADLINE_UNMET);
        }

        @Test
        @DisplayName("이미 마감된 방은 처리할 수 없다")
        void rejectsWhenNotRecruiting() {
            groupOrder.closeByHost(HOST_ID, 3, 20_000);

            assertThatThrownBy(() -> groupOrder.closeAtDeadline(3, 20_000, DEADLINE))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("방장의 취소")
    class CancelByHost {

        static final String REASON = "가게가 갑자기 문을 닫았어요";

        @Test
        @DisplayName("모집중에는 사유 없이 취소할 수 있다")
        void cancelsWhileRecruitingWithoutReason() {
            groupOrder.cancelByHost(HOST_ID, null);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CANCELED);
            assertThat(groupOrder.getCancelType()).isEqualTo(CancelType.HOST_WHILE_RECRUITING);
            assertThat(groupOrder.getCancelReason()).isNull();
        }

        @Test
        @DisplayName("모집중에 사유를 적으면 그대로 남는다")
        void keepsReasonWhileRecruiting() {
            groupOrder.cancelByHost(HOST_ID, REASON);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CANCELED);
            assertThat(groupOrder.getCancelType()).isEqualTo(CancelType.HOST_WHILE_RECRUITING);
            assertThat(groupOrder.getCancelReason()).isEqualTo(REASON);
        }

        @Test
        @DisplayName("마감 뒤에는 사유를 적으면 취소할 수 있다")
        void cancelsAfterClosedWithReason() {
            groupOrder.closeByHost(HOST_ID, 3, 20_000);

            groupOrder.cancelByHost(HOST_ID, REASON);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CANCELED);
            assertThat(groupOrder.getCancelType()).isEqualTo(CancelType.HOST_AFTER_CLOSED);
            assertThat(groupOrder.getCancelReason()).isEqualTo(REASON);
        }

        @Test
        @DisplayName("마감 뒤에는 사유 없이 취소할 수 없고 마감으로 남는다")
        void rejectsAfterClosedWithoutReason() {
            groupOrder.closeByHost(HOST_ID, 3, 20_000);

            assertThatThrownBy(() -> groupOrder.cancelByHost(HOST_ID, null))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CLOSED);
            assertThat(groupOrder.getCancelType()).isNull();
        }

        @Test
        @DisplayName("마감 뒤에 공백만 적은 사유는 사유가 없는 것으로 본다")
        void rejectsAfterClosedWithBlankReason() {
            groupOrder.closeByHost(HOST_ID, 3, 20_000);

            assertThatThrownBy(() -> groupOrder.cancelByHost(HOST_ID, "   "))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CLOSED);
            assertThat(groupOrder.getCancelReason()).isNull();
        }

        @Test
        @DisplayName("방장이 아닌 사람은 취소할 수 없다")
        void rejectsNonHost() {
            assertThatThrownBy(() -> groupOrder.cancelByHost(OTHER_ID, REASON))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.RECRUITING);
            assertThat(groupOrder.getCancelType()).isNull();
            assertThat(groupOrder.getCancelReason()).isNull();
        }

        @Test
        @DisplayName("이미 취소된 방은 다시 취소할 수 없고 처음 기록이 남는다")
        void rejectsWhenAlreadyCanceled() {
            groupOrder.cancelByHost(HOST_ID, null);

            assertThatThrownBy(() -> groupOrder.cancelByHost(HOST_ID, REASON))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getCancelType()).isEqualTo(CancelType.HOST_WHILE_RECRUITING);
            assertThat(groupOrder.getCancelReason()).isNull();
        }

        @Test
        @DisplayName("마감 시각에 자동 취소된 방을 방장이 다시 취소해서 기록을 덮을 수 없다")
        void rejectsAfterDeadlineCancel() {
            groupOrder.closeAtDeadline(1, 20_000, DEADLINE);

            assertThatThrownBy(() -> groupOrder.cancelByHost(HOST_ID, REASON))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getCancelType()).isEqualTo(CancelType.DEADLINE_UNMET);
        }
    }
}
