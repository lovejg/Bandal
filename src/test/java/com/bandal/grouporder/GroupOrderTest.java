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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// DB 없이 GroupOrder의 전이 규칙만 확인한다.
class GroupOrderTest {

    // 운영 DB처럼 작지 않은 id를 쓴다
    static final Long HOST_ID = 1_000L;
    static final Long OTHER_ID = 1_001L;
    static final long MIN_ORDER_AMOUNT = 15_000;
    static final Instant DEADLINE = Instant.parse("2026-09-17T10:30:00Z");

    GroupOrder groupOrder;
    University university;
    PickupSpot pickupSpot;
    User host;

    @BeforeEach
    void setUp() {
        university = new University("한국대학교", "hankuk.ac.kr");
        pickupSpot = new PickupSpot(university, "제1기숙사 로비", null);
        host = new User(university, "kim@hankuk.ac.kr", "hashed-password", "배고파");
        // 저장하지 않은 엔티티라 id가 null이다. 방장 확인을 테스트하려고 id만 직접 넣는다.
        // 실제로는 엔티티의 id(DB에서 읽음)와 요청자 id(요청에서 읽음)가 서로 다른 Long 객체라서,
        // HOST_ID를 그대로 넣지 않고 값만 같은 새 Long을 넣는다.
        ReflectionTestUtils.setField(host, "id", Long.valueOf(HOST_ID.longValue()));
        // 대학 비교도 id로 하므로 id가 둘 다 null이면 서로 다른 대학이 같아 보인다
        ReflectionTestUtils.setField(university, "id", 10L);

        groupOrder = new GroupOrder(host, pickupSpot, "○○마라탕", MIN_ORDER_AMOUNT, DEADLINE, 4);
    }

    @Nested
    @DisplayName("참여 가능 검사")
    class CheckJoinable {

        static final Instant BEFORE_DEADLINE = DEADLINE.minusSeconds(60);

        User member;

        @BeforeEach
        void addMember() {
            member = new User(university, "lee@hankuk.ac.kr", "hashed-password", "마라탕러버");
        }

        @Test
        @DisplayName("같은 대학 사람이 마감 전에 빈자리에 들어올 수 있다")
        void allowsJoin() {
            assertThatCode(() -> groupOrder.checkJoinable(member, 1, BEFORE_DEADLINE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("마지막 한 자리도 들어올 수 있다")
        void allowsLastSeat() {
            // 정원 4명에 현재 3명
            assertThatCode(() -> groupOrder.checkJoinable(member, 3, BEFORE_DEADLINE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("정원이 다 찼으면 들어올 수 없다")
        void rejectsWhenFull() {
            assertThatThrownBy(() -> groupOrder.checkJoinable(member, 4, BEFORE_DEADLINE))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("마감 시각 정각에는 이미 늦었다")
        void rejectsAtDeadline() {
            assertThatThrownBy(() -> groupOrder.checkJoinable(member, 1, DEADLINE))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("마감된 방에는 들어올 수 없다")
        void rejectsWhenClosed() {
            groupOrder.closeByHost(HOST_ID, 2, 20_000);

            assertThatThrownBy(() -> groupOrder.checkJoinable(member, 2, BEFORE_DEADLINE))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("취소된 방에는 들어올 수 없다")
        void rejectsWhenCanceled() {
            groupOrder.cancelByHost(HOST_ID, null);

            assertThatThrownBy(() -> groupOrder.checkJoinable(member, 1, BEFORE_DEADLINE))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("다른 대학 사람은 들어올 수 없다")
        void rejectsOtherUniversity() {
            University otherUniversity = new University("민국대학교", "minguk.ac.kr");
            ReflectionTestUtils.setField(otherUniversity, "id", 11L);
            User outsider = new User(otherUniversity, "park@minguk.ac.kr", "hashed-password", "외부인");

            assertThatThrownBy(() -> groupOrder.checkJoinable(outsider, 1, BEFORE_DEADLINE))
                    .isInstanceOf(IllegalStateException.class);
        }
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

    // 여기부터 정산 (ADR-029)
    // 마감 -> 정산중 -> 주문완료 -> 배달완료

    // 방을 마감 상태로 만든다. 3명, 20,000원이면 조건을 넘는다
    void close() {
        groupOrder.closeByHost(HOST_ID, 3, 20_000);
    }

    // 정산중까지 보낸다. 아직 아무도 송금하지 않은 상태
    void startSettlement() {
        close();
        groupOrder.startSettlement(HOST_ID, 3_500, 19_500L, 0);
    }

    @Nested
    @DisplayName("배달비 입력")
    class StartSettlement {

        @Test
        @DisplayName("마감된 방에 배달비를 입력하면 정산중이 된다")
        void startsSettlement() {
            close();

            groupOrder.startSettlement(HOST_ID, 3_500, 19_500L, 0);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.SETTLING);
            assertThat(groupOrder.getDeliveryFee()).isEqualTo(3_500);
            assertThat(groupOrder.getTotalPaidAmount()).isEqualTo(19_500);
        }

        @Test
        @DisplayName("결제 금액은 선택 입력이라 없어도 된다")
        void allowsNullTotalPaidAmount() {
            close();

            groupOrder.startSettlement(HOST_ID, 3_500, null, 0);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.SETTLING);
            assertThat(groupOrder.getTotalPaidAmount()).isNull();
        }

        @Test
        @DisplayName("배달비 0원은 무료라는 뜻이라 허용한다")
        void allowsFreeDelivery() {
            close();

            assertThatCode(() -> groupOrder.startSettlement(HOST_ID, 0, null, 0))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("배달비가 음수면 값 자체가 틀렸다")
        void rejectsNegativeFee() {
            close();

            assertThatThrownBy(() -> groupOrder.startSettlement(HOST_ID, -1, null, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("모집중인 방에는 배달비를 입력할 수 없다")
        void rejectsWhileRecruiting() {
            assertThatThrownBy(() -> groupOrder.startSettlement(HOST_ID, 3_500, null, 0))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.RECRUITING);
        }

        @Test
        @DisplayName("방장이 아니면 배달비를 입력할 수 없다")
        void rejectsNonHost() {
            close();

            assertThatThrownBy(() -> groupOrder.startSettlement(OTHER_ID, 3_500, null, 0))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("아직 아무도 송금하지 않았으면 배달비를 다시 입력할 수 있다")
        void allowsRetypeBeforeAnyonePaid() {
            startSettlement();

            groupOrder.startSettlement(HOST_ID, 4_000, null, 0);

            assertThat(groupOrder.getDeliveryFee()).isEqualTo(4_000);
        }

        @Test
        @DisplayName("한 명이라도 보냈다고 표시했으면 배달비를 바꿀 수 없다")
        void rejectsRetypeAfterSomeonePaid() {
            startSettlement();

            // 그 금액을 믿고 보낸 사람이 있다 (ADR-030)
            assertThatThrownBy(() -> groupOrder.startSettlement(HOST_ID, 4_000, null, 1))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getDeliveryFee()).isEqualTo(3_500);
        }

        @Test
        @DisplayName("주문완료 뒤에는 배달비를 바꿀 수 없다")
        void rejectsAfterOrdered() {
            startSettlement();
            groupOrder.markOrdered(HOST_ID, 0);

            assertThatThrownBy(() -> groupOrder.startSettlement(HOST_ID, 4_000, null, 0))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("주문완료 전이")
    class MarkOrdered {

        @Test
        @DisplayName("전원 입금이 확인되면 주문완료로 넘어간다")
        void marksOrdered() {
            startSettlement();

            groupOrder.markOrdered(HOST_ID, 0);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.ORDERED);
        }

        @Test
        @DisplayName("확인 안 된 사람이 남아 있으면 주문할 수 없다")
        void rejectsWhenSomeoneUnconfirmed() {
            startSettlement();

            assertThatThrownBy(() -> groupOrder.markOrdered(HOST_ID, 1))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.SETTLING);
        }

        @Test
        @DisplayName("배달비를 입력하지 않은 마감 상태에서는 주문할 수 없다")
        void rejectsFromClosed() {
            close();

            assertThatThrownBy(() -> groupOrder.markOrdered(HOST_ID, 0))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("방장만 주문완료로 넘길 수 있다")
        void rejectsNonHost() {
            startSettlement();

            assertThatThrownBy(() -> groupOrder.markOrdered(OTHER_ID, 0))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("이미 주문완료된 방을 다시 주문완료로 만들 수 없다")
        void rejectsTwice() {
            startSettlement();
            groupOrder.markOrdered(HOST_ID, 0);

            assertThatThrownBy(() -> groupOrder.markOrdered(HOST_ID, 0))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("배달완료 전이")
    class MarkDelivered {

        @Test
        @DisplayName("주문완료된 방을 배달완료로 넘긴다")
        void marksDelivered() {
            startSettlement();
            groupOrder.markOrdered(HOST_ID, 0);

            groupOrder.markDelivered(HOST_ID);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.DELIVERED);
        }

        @Test
        @DisplayName("정산중에서 배달완료로 건너뛸 수 없다")
        void rejectsFromSettling() {
            startSettlement();

            assertThatThrownBy(() -> groupOrder.markDelivered(HOST_ID))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("방장만 배달완료로 넘길 수 있다")
        void rejectsNonHost() {
            startSettlement();
            groupOrder.markOrdered(HOST_ID, 0);

            assertThatThrownBy(() -> groupOrder.markDelivered(OTHER_ID))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("정산중 취소")
    class CancelWhileSettling {

        static final String REASON = "가게가 문을 닫았습니다";

        @Test
        @DisplayName("사유를 적으면 정산중에도 취소할 수 있다")
        void cancels() {
            startSettlement();

            groupOrder.cancelByHost(HOST_ID, REASON);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CANCELED);
            // 마감 뒤 취소와 구분해야 나중에 감점을 다르게 줄 수 있다 (ADR-021 수정)
            assertThat(groupOrder.getCancelType()).isEqualTo(CancelType.HOST_AFTER_SETTLING);
            assertThat(groupOrder.getCancelReason()).isEqualTo(REASON);
        }

        @Test
        @DisplayName("정산중 취소는 사유가 없으면 거절한다")
        void requiresReason() {
            startSettlement();

            assertThatThrownBy(() -> groupOrder.cancelByHost(HOST_ID, null))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.SETTLING);
        }

        @Test
        @DisplayName("주문완료 뒤에는 취소할 수 없다. 이미 돈이 나갔다")
        void rejectsAfterOrdered() {
            startSettlement();
            groupOrder.markOrdered(HOST_ID, 0);

            assertThatThrownBy(() -> groupOrder.cancelByHost(HOST_ID, REASON))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.ORDERED);
        }
    }
}
