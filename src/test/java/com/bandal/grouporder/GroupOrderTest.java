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
    static final Instant BEFORE_DEADLINE = DEADLINE.minusSeconds(60);
    // 스케줄러가 없어서 마감 시각이 지나도 모집중으로 남은 방을 방장이 뒤늦게 연 시각 (ADR-040)
    static final Instant AFTER_DEADLINE = DEADLINE.plusSeconds(60 * 30);

    GroupOrder groupOrder;
    University university;
    PickupSpot pickupSpot;
    User host;

    @BeforeEach
    void setUp() {
        university = new University("한국대학교", "hankuk.ac.kr");
        pickupSpot = new PickupSpot(university, "제1기숙사 로비", null);
        host = new User(university, "kim@hankuk.ac.kr", "hashed-password", "배고파");
        // 계좌가 없으면 방을 만들 수 없다 (ADR-031)
        host.registerAccount("한국은행", "110-123-456789", "김민수");
        // 저장하지 않은 엔티티라 id가 null이다. 방장 확인을 테스트하려고 id만 직접 넣는다.
        // 실제로는 엔티티의 id(DB에서 읽음)와 요청자 id(요청에서 읽음)가 서로 다른 Long 객체라서,
        // HOST_ID를 그대로 넣지 않고 값만 같은 새 Long을 넣는다.
        ReflectionTestUtils.setField(host, "id", Long.valueOf(HOST_ID.longValue()));
        // 대학 비교도 id로 하므로 id가 둘 다 null이면 서로 다른 대학이 같아 보인다
        ReflectionTestUtils.setField(university, "id", 10L);

        groupOrder = new GroupOrder(host, pickupSpot, "○○마라탕", MIN_ORDER_AMOUNT, DEADLINE, 4);
    }

    @Nested
    @DisplayName("방 만들기")
    class Create {

        @Test
        @DisplayName("방장과 같은 학교 거점이면 만들 수 있다")
        void allowsOwnUniversitySpot() {
            assertThatCode(() -> new GroupOrder(host, pickupSpot, "○○마라탕", MIN_ORDER_AMOUNT, DEADLINE, 4))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("다른 학교 거점으로는 만들 수 없다")
        void rejectsOtherUniversitySpot() {
            // 한국대 방장이 민국대 거점 id를 보낸 상황. 막지 않으면 이 방은 민국대 목록에 뜬다 (ADR-036)
            University otherUniversity = new University("민국대학교", "minguk.ac.kr");
            ReflectionTestUtils.setField(otherUniversity, "id", 11L);
            PickupSpot otherSpot = new PickupSpot(otherUniversity, "민국대 정문", null);

            assertThatThrownBy(() -> new GroupOrder(host, otherSpot, "○○마라탕", MIN_ORDER_AMOUNT, DEADLINE, 4))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("거점이 없으면 만들 수 없다")
        void rejectsNullSpot() {
            assertThatThrownBy(() -> new GroupOrder(host, null, "○○마라탕", MIN_ORDER_AMOUNT, DEADLINE, 4))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("방장인가")
    class IsHost {

        @Test
        @DisplayName("방장 id면 true다")
        void trueForHost() {
            // 값만 같은 새 Long으로 묻는다. ==로 비교했다면 여기서 false가 나온다
            assertThat(groupOrder.isHost(Long.valueOf(HOST_ID.longValue()))).isTrue();
        }

        @Test
        @DisplayName("다른 사람 id면 예외 없이 false다")
        void falseForOthers() {
            // 검수 목록에서 참여자마다 묻는다. 방장이 아닌 건 에러가 아니다 (ADR-039)
            assertThat(groupOrder.isHost(OTHER_ID)).isFalse();
        }

        @Test
        @DisplayName("id가 null이면 false다")
        void falseForNull() {
            assertThat(groupOrder.isHost(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("참여 가능 검사")
    class CheckJoinable {

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
            groupOrder.closeByHost(HOST_ID, 2, 20_000, BEFORE_DEADLINE);

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
            groupOrder.closeByHost(HOST_ID, 3, 20_000, BEFORE_DEADLINE);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CLOSED);
        }

        @Test
        @DisplayName("딱 2명, 딱 최소주문금액이어도 마감된다")
        void closesAtBoundary() {
            groupOrder.closeByHost(HOST_ID, 2, MIN_ORDER_AMOUNT, BEFORE_DEADLINE);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CLOSED);
        }

        @Test
        @DisplayName("방장이 아닌 사람은 마감할 수 없다")
        void rejectsNonHost() {
            assertThatThrownBy(() -> groupOrder.closeByHost(OTHER_ID, 3, 20_000, BEFORE_DEADLINE))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.RECRUITING);
        }

        @Test
        @DisplayName("방장 혼자면 마감할 수 없고 모집중으로 남는다")
        void rejectsSinglePerson() {
            assertThatThrownBy(() -> groupOrder.closeByHost(HOST_ID, 1, 20_000, BEFORE_DEADLINE))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.RECRUITING);
        }

        @Test
        @DisplayName("최소주문금액에 1원이라도 모자라면 마감할 수 없고, 취소되지도 않는다")
        void rejectsShortAmount() {
            assertThatThrownBy(() -> groupOrder.closeByHost(HOST_ID, 3, MIN_ORDER_AMOUNT - 1, BEFORE_DEADLINE))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.RECRUITING);
            assertThat(groupOrder.getCancelReason()).isNull();
            assertThat(groupOrder.getCancelType()).isNull();
        }

        @Test
        @DisplayName("마감 1초 전에 모자라면 아직 수동 마감 규칙이라 거절만 된다")
        void rejectsShortAmountOneSecondBeforeDeadline() {
            assertThatThrownBy(() -> groupOrder.closeByHost(HOST_ID, 3, MIN_ORDER_AMOUNT - 1, DEADLINE.minusSeconds(1)))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.RECRUITING);
        }

        @Test
        @DisplayName("이미 마감된 방은 다시 마감할 수 없다")
        void rejectsWhenNotRecruiting() {
            groupOrder.closeByHost(HOST_ID, 3, 20_000, BEFORE_DEADLINE);

            assertThatThrownBy(() -> groupOrder.closeByHost(HOST_ID, 3, 20_000, BEFORE_DEADLINE))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // 스케줄러가 없어서 마감 시각이 지나도 모집중인 방. 버튼이 자동 마감 규칙을 대신 일으킨다 (ADR-040)
    @Nested
    @DisplayName("마감 시각이 지난 뒤 방장의 마감")
    class CloseByHostAfterDeadline {

        @Test
        @DisplayName("조건을 채웠으면 마감되고 취소 기록은 없다")
        void closesWhenMet() {
            groupOrder.closeByHost(HOST_ID, 3, 20_000, AFTER_DEADLINE);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CLOSED);
            assertThat(groupOrder.getCancelType()).isNull();
            assertThat(groupOrder.getCancelReason()).isNull();
        }

        @Test
        @DisplayName("금액이 모자라면 거절하지 않고 자동 취소된다")
        void cancelsWhenShortAmount() {
            // 시각이 지나 참여도 담기도 막혀서 모자란 금액을 채울 길이 없다. 거절하면 방이 영원히 모집중이다
            assertThatCode(() -> groupOrder.closeByHost(HOST_ID, 3, MIN_ORDER_AMOUNT - 1, AFTER_DEADLINE))
                    .doesNotThrowAnyException();

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CANCELED);
            assertThat(groupOrder.getCancelType()).isEqualTo(CancelType.DEADLINE_UNMET);
            assertThat(groupOrder.getCancelReason()).isNotBlank();
        }

        @Test
        @DisplayName("방장 혼자면 자동 취소된다")
        void cancelsWhenSinglePerson() {
            groupOrder.closeByHost(HOST_ID, 1, 20_000, AFTER_DEADLINE);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CANCELED);
            assertThat(groupOrder.getCancelType()).isEqualTo(CancelType.DEADLINE_UNMET);
        }

        @Test
        @DisplayName("정확히 마감 시각이면 이미 지난 것으로 본다")
        void deadlineItselfCountsAsPassed() {
            // 참여와 담기가 정각부터 막히니 마감도 정각부터 자동 마감 규칙이다
            groupOrder.closeByHost(HOST_ID, 3, MIN_ORDER_AMOUNT - 1, DEADLINE);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CANCELED);
            assertThat(groupOrder.getCancelType()).isEqualTo(CancelType.DEADLINE_UNMET);
        }

        @Test
        @DisplayName("방장이 아닌 사람은 시각이 지났어도 마감도 취소도 못 시킨다")
        void rejectsNonHost() {
            // 시각을 방장 검사보다 먼저 보면, 참여자 한 명의 요청으로 방이 취소된다
            assertThatThrownBy(() -> groupOrder.closeByHost(OTHER_ID, 3, MIN_ORDER_AMOUNT - 1, AFTER_DEADLINE))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.RECRUITING);
            assertThat(groupOrder.getCancelType()).isNull();
        }

        @Test
        @DisplayName("이미 마감된 방은 시각이 지났어도 다시 처리하지 않는다")
        void rejectsWhenNotRecruiting() {
            groupOrder.closeByHost(HOST_ID, 3, 20_000, BEFORE_DEADLINE);

            assertThatThrownBy(() -> groupOrder.closeByHost(HOST_ID, 1, 0, AFTER_DEADLINE))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CLOSED);
        }

        @Test
        @DisplayName("취소된 방은 시각이 지났어도 기록을 덮지 않는다")
        void rejectsWhenCanceled() {
            groupOrder.cancelByHost(HOST_ID, null);

            assertThatThrownBy(() -> groupOrder.closeByHost(HOST_ID, 1, 0, AFTER_DEADLINE))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getCancelType()).isEqualTo(CancelType.HOST_WHILE_RECRUITING);
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
            groupOrder.closeByHost(HOST_ID, 3, 20_000, BEFORE_DEADLINE);

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
            groupOrder.closeByHost(HOST_ID, 3, 20_000, BEFORE_DEADLINE);

            groupOrder.cancelByHost(HOST_ID, REASON);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CANCELED);
            assertThat(groupOrder.getCancelType()).isEqualTo(CancelType.HOST_AFTER_CLOSED);
            assertThat(groupOrder.getCancelReason()).isEqualTo(REASON);
        }

        @Test
        @DisplayName("마감 뒤에는 사유 없이 취소할 수 없고 마감으로 남는다")
        void rejectsAfterClosedWithoutReason() {
            groupOrder.closeByHost(HOST_ID, 3, 20_000, BEFORE_DEADLINE);

            assertThatThrownBy(() -> groupOrder.cancelByHost(HOST_ID, null))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CLOSED);
            assertThat(groupOrder.getCancelType()).isNull();
        }

        @Test
        @DisplayName("마감 뒤에 공백만 적은 사유는 사유가 없는 것으로 본다")
        void rejectsAfterClosedWithBlankReason() {
            groupOrder.closeByHost(HOST_ID, 3, 20_000, BEFORE_DEADLINE);

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

    @Nested
    @DisplayName("배달비 입력으로 정산 시작")
    class StartSettlement {

        static final long DELIVERY_FEE = 3_500;

        // 방장 포함 3명, 메뉴 20,000원으로 마감까지 끝낸다
        void close() {
            groupOrder.closeByHost(HOST_ID, 3, 20_000, BEFORE_DEADLINE);
        }

        @Test
        @DisplayName("마감된 방에서 방장이 입력하면 정산중이 되고 배달비가 남는다")
        void startsSettlement() {
            close();

            groupOrder.startSettlement(HOST_ID, DELIVERY_FEE);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.SETTLING);
            assertThat(groupOrder.getDeliveryFee()).isEqualTo(DELIVERY_FEE);
            // 실제 결제금액은 주문완료 때 받는다 (ADR-042)
            assertThat(groupOrder.getTotalPaidAmount()).isNull();
        }

        @Test
        @DisplayName("배달비 0원(무료배달)도 받는다")
        void acceptsFreeDelivery() {
            close();

            groupOrder.startSettlement(HOST_ID, 0);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.SETTLING);
            assertThat(groupOrder.getDeliveryFee()).isZero();
        }

        @Test
        @DisplayName("방장이 아니면 거절하고 마감으로 남는다")
        void rejectsNonHost() {
            close();

            assertThatThrownBy(() -> groupOrder.startSettlement(OTHER_ID, DELIVERY_FEE))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CLOSED);
            assertThat(groupOrder.getDeliveryFee()).isNull();
        }

        @Test
        @DisplayName("모집중인 방에는 입력할 수 없다")
        void rejectsWhileRecruiting() {
            assertThatThrownBy(() -> groupOrder.startSettlement(HOST_ID, DELIVERY_FEE))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.RECRUITING);
            assertThat(groupOrder.getDeliveryFee()).isNull();
        }

        @Test
        @DisplayName("이미 정산중이면 두 번째 입력은 거절하고 처음 배달비가 남는다")
        void rejectsSecondStart() {
            close();
            groupOrder.startSettlement(HOST_ID, DELIVERY_FEE);

            assertThatThrownBy(() -> groupOrder.startSettlement(HOST_ID, 5_000))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getDeliveryFee()).isEqualTo(DELIVERY_FEE);
        }

        @Test
        @DisplayName("취소된 방에는 입력할 수 없다")
        void rejectsCanceled() {
            close();
            groupOrder.cancelByHost(HOST_ID, "가게가 문을 닫았어요");

            assertThatThrownBy(() -> groupOrder.startSettlement(HOST_ID, DELIVERY_FEE))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CANCELED);
        }

        @Test
        @DisplayName("배달비가 음수면 값이 틀린 것이라 거절하고 아무것도 적지 않는다")
        void rejectsNegativeFee() {
            close();

            assertThatThrownBy(() -> groupOrder.startSettlement(HOST_ID, -1))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.CLOSED);
            assertThat(groupOrder.getDeliveryFee()).isNull();
        }
    }

    // 주문완료와 배달완료 전이의 단위 테스트는 그 단계에서 다시 만든다.
    // 예전 것은 커밋 8888991에 남아 있다
}
