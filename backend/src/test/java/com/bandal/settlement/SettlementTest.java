package com.bandal.settlement;

import com.bandal.common.ForbiddenException;
import com.bandal.grouporder.CancelType;
import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderStatus;
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

// DB 없이 정산표 한 줄의 규칙만 확인한다
class SettlementTest {

    static final Long HOST_ID = 2_000L;
    static final Long MEMBER_ID = 2_001L;
    static final Long OTHER_ID = 2_002L;
    static final Instant NOW = Instant.parse("2026-09-26T11:00:00Z");

    GroupOrder groupOrder;
    User host;
    User member;

    @BeforeEach
    void setUp() {
        University university = new University("한국대학교", "hankuk.ac.kr");
        ReflectionTestUtils.setField(university, "id", 10L);
        PickupSpot pickupSpot = new PickupSpot(university, "제1기숙사 로비", null);

        host = new User(university, "kim@hankuk.ac.kr", "hashed-password", "배고파");
        // 계좌가 없으면 방을 만들 수 없다
        host.registerAccount("한국은행", "110-123-456789", "김민수");
        member = new User(university, "lee@hankuk.ac.kr", "hashed-password", "마라탕러버");
        // 저장하지 않은 엔티티라 id가 null이다. 값은 같지만 다른 Long 객체를 넣어서
        // 실제 상황(DB에서 읽은 id와 요청에서 온 id)과 같게 만든다
        ReflectionTestUtils.setField(host, "id", Long.valueOf(HOST_ID.longValue()));
        ReflectionTestUtils.setField(member, "id", Long.valueOf(MEMBER_ID.longValue()));

        groupOrder = new GroupOrder(host, pickupSpot, "○○마라탕", 15_000,
                Instant.parse("2026-09-26T10:30:00Z"), 4);
        // 표시와 확인은 정산중에만 받는다. 줄은 정산을 시작할 때 생기므로 여기까지 보내 둔다
        groupOrder.closeByHost(HOST_ID, 2, 17_000, Instant.parse("2026-09-26T10:00:00Z"));
        groupOrder.startSettlement(HOST_ID, 2_334, NOW);
    }

    // 상태만 필요한 테스트라 주문완료, 배달완료 전이를 거치지 않고 상태를 바로 바꾼다
    void forceStatus(GroupOrderStatus status) {
        ReflectionTestUtils.setField(groupOrder, "status", status);
    }

    // 참여자 줄. 메뉴 9,000원에 배달비 분담 1,166원
    Settlement memberLine() {
        return new Settlement(groupOrder, member, 9_000, 1_166, false, NOW);
    }

    // 방장 줄. 나누어떨어지지 않는 나머지를 방장이 떠안는다
    Settlement hostLine() {
        return new Settlement(groupOrder, host, 8_000, 1_168, true, NOW);
    }

    @Nested
    @DisplayName("만들 때")
    class Creation {

        @Test
        @DisplayName("낼 금액은 메뉴 합계와 배달비 분담을 더한 값이다")
        void sumsTotalAmount() {
            Settlement line = memberLine();

            assertThat(line.getMenuTotalAmount()).isEqualTo(9_000);
            assertThat(line.getFeeShare()).isEqualTo(1_166);
            assertThat(line.getTotalAmount()).isEqualTo(10_166);
        }

        @Test
        @DisplayName("참여자 줄은 두 시각이 모두 비어 있다")
        void memberStartsUnpaid() {
            Settlement line = memberLine();

            assertThat(line.getMarkedPaidAt()).isNull();
            assertThat(line.getConfirmedPaidAt()).isNull();
            assertThat(line.isConfirmed()).isFalse();
            assertThat(line.isHost()).isFalse();
        }

        @Test
        @DisplayName("방장 줄은 만들 때부터 입금이 정리된 상태다")
        void hostStartsConfirmed() {
            // 방장은 자기에게 송금하지 않는다. 비워두면 "전원 확인" 조건마다
            // 방장 예외 분기가 생긴다
            Settlement line = hostLine();

            assertThat(line.getMarkedPaidAt()).isEqualTo(NOW);
            assertThat(line.getConfirmedPaidAt()).isEqualTo(NOW);
            assertThat(line.isConfirmed()).isTrue();
            assertThat(line.isHost()).isTrue();
        }
    }

    @Nested
    @DisplayName("보냈다고 표시")
    class MarkPaid {

        @Test
        @DisplayName("본인이 누르면 시각이 남는다")
        void marks() {
            Settlement line = memberLine();

            line.markPaid(MEMBER_ID, NOW);

            assertThat(line.getMarkedPaidAt()).isEqualTo(NOW);
            // 방장이 확인한 건 아니다. 아직 정리된 게 아니다
            assertThat(line.isConfirmed()).isFalse();
        }

        @Test
        @DisplayName("남의 줄에는 누를 수 없다")
        void rejectsOther() {
            Settlement line = memberLine();

            assertThatThrownBy(() -> line.markPaid(OTHER_ID, NOW))
                    .isInstanceOf(ForbiddenException.class);

            assertThat(line.getMarkedPaidAt()).isNull();
        }

        @Test
        @DisplayName("방장이 남의 줄에 대신 누를 수도 없다")
        void rejectsHost() {
            Settlement line = memberLine();

            assertThatThrownBy(() -> line.markPaid(HOST_ID, NOW))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        @DisplayName("두 번 눌러도 처음 보냈다고 한 시각이 남는다")
        void keepsFirstMarkedTime() {
            Settlement line = memberLine();
            line.markPaid(MEMBER_ID, NOW);

            // 화면을 새로고침하다 또 누를 수 있다. 알려줘도 사용자가 할 일이 없어서 조용히 넘긴다.
            // 다만 시각이 밀리면 "14시에 보냈다고 했는데 방장이 3시간을 안 봤다"는 근거가 사라진다
            line.markPaid(MEMBER_ID, NOW.plusSeconds(1_800));

            assertThat(line.getMarkedPaidAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("이미 확인된 줄에는 누를 필요가 없다")
        void rejectsWhenAlreadyConfirmed() {
            Settlement line = memberLine();
            line.confirm(HOST_ID, NOW);

            assertThatThrownBy(() -> line.markPaid(MEMBER_ID, NOW.plusSeconds(60)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("입금 확인")
    class Confirm {

        @Test
        @DisplayName("방장이 누르면 정리된다")
        void confirms() {
            Settlement line = memberLine();
            line.markPaid(MEMBER_ID, NOW);

            line.confirm(HOST_ID, NOW.plusSeconds(60));

            assertThat(line.getConfirmedPaidAt()).isEqualTo(NOW.plusSeconds(60));
            assertThat(line.isConfirmed()).isTrue();
        }

        @Test
        @DisplayName("참여자가 스스로 확인할 수는 없다")
        void rejectsMember() {
            // 이걸 허용하면 이체도 안 하고 방장 돈으로 주문을 시킬 수 있다
            Settlement line = memberLine();

            assertThatThrownBy(() -> line.confirm(MEMBER_ID, NOW))
                    .isInstanceOf(ForbiddenException.class);

            assertThat(line.isConfirmed()).isFalse();
        }

        @Test
        @DisplayName("참여자가 보냈다고 안 눌렀어도 방장은 확인할 수 있다")
        void confirmsWithoutMark() {
            // 참여자가 이체만 하고 누르는 걸 잊었을 수 있다
            Settlement line = memberLine();

            line.confirm(HOST_ID, NOW);

            assertThat(line.isConfirmed()).isTrue();
        }

        @Test
        @DisplayName("이미 확인한 줄을 다시 확인할 수 없다")
        void rejectsTwice() {
            Settlement line = memberLine();
            line.confirm(HOST_ID, NOW);

            assertThatThrownBy(() -> line.confirm(HOST_ID, NOW.plusSeconds(60)))
                    .isInstanceOf(IllegalStateException.class);

            // 처음 확인한 시각이 밀리지 않는다
            assertThat(line.getConfirmedPaidAt()).isEqualTo(NOW);
        }
    }

    // 방장의 확인 취소
    @Nested
    @DisplayName("확인 취소")
    class RevokeConfirm {

        static final Instant CONFIRMED_AT = NOW.plusSeconds(60);
        static final Instant REVOKED_AT = NOW.plusSeconds(600);

        @Test
        @DisplayName("확인을 비우고 취소한 시각을 남긴다")
        void revokes() {
            Settlement line = memberLine();
            line.confirm(HOST_ID, CONFIRMED_AT);

            line.revokeConfirm(HOST_ID, REVOKED_AT);

            assertThat(line.getConfirmedPaidAt()).isNull();
            assertThat(line.isConfirmed()).isFalse();
            assertThat(line.getConfirmRevokedAt()).isEqualTo(REVOKED_AT);
        }

        @Test
        @DisplayName("참여자의 보냈어요 기록은 그대로 둔다")
        void keepsMark() {
            // 표시는 참여자의 주장이다. 방장이 지울 수 없다
            Settlement line = memberLine();
            line.markPaid(MEMBER_ID, NOW);
            line.confirm(HOST_ID, CONFIRMED_AT);

            line.revokeConfirm(HOST_ID, REVOKED_AT);

            assertThat(line.getMarkedPaidAt()).isEqualTo(NOW);
            // "보냈다는데 확인 안 됨"으로 돌아간다
            assertThat(line.getConfirmedPaidAt()).isNull();
        }

        @Test
        @DisplayName("확인 안 된 줄이면 아무것도 바꾸지 않는다")
        void ignoresUnconfirmed() {
            Settlement line = memberLine();

            line.revokeConfirm(HOST_ID, REVOKED_AT);

            // 되돌린 게 없으니 흔적도 없다. 흔적이 있으면 "확인한 적이 있다"는 뜻이 된다
            assertThat(line.getConfirmRevokedAt()).isNull();
            assertThat(line.getConfirmedPaidAt()).isNull();
        }

        @Test
        @DisplayName("참여자는 확인을 취소할 수 없다")
        void rejectsMember() {
            Settlement line = memberLine();
            line.confirm(HOST_ID, CONFIRMED_AT);

            assertThatThrownBy(() -> line.revokeConfirm(MEMBER_ID, REVOKED_AT))
                    .isInstanceOf(ForbiddenException.class);

            assertThat(line.getConfirmedPaidAt()).isEqualTo(CONFIRMED_AT);
            assertThat(line.getConfirmRevokedAt()).isNull();
        }

        @Test
        @DisplayName("확인 안 된 줄이어도 참여자가 누르면 거절한다")
        void rejectsMemberOnUnconfirmed() {
            // 할 일이 없다고 조용히 넘기면, 권한 없는 요청이 성공으로 보인다
            Settlement line = memberLine();

            assertThatThrownBy(() -> line.revokeConfirm(MEMBER_ID, REVOKED_AT))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        @DisplayName("방장 줄은 확인을 취소할 수 없다")
        void rejectsHostLine() {
            // 방장 줄이 비면 전원 확인이 영영 안 채워진다
            Settlement line = hostLine();

            assertThatThrownBy(() -> line.revokeConfirm(HOST_ID, REVOKED_AT))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(line.isConfirmed()).isTrue();
            assertThat(line.getConfirmRevokedAt()).isNull();
        }

        @Test
        @DisplayName("정산중이 아니면 취소할 수 없다")
        void rejectsAfterOrdered() {
            Settlement line = memberLine();
            line.confirm(HOST_ID, CONFIRMED_AT);
            forceStatus(GroupOrderStatus.ORDERED);

            assertThatThrownBy(() -> line.revokeConfirm(HOST_ID, REVOKED_AT))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(line.getConfirmedPaidAt()).isEqualTo(CONFIRMED_AT);
        }

        @Test
        @DisplayName("취소한 뒤 다시 확인할 수 있고, 취소한 흔적은 남는다")
        void reconfirmKeepsTrace() {
            Settlement line = memberLine();
            line.confirm(HOST_ID, CONFIRMED_AT);
            line.revokeConfirm(HOST_ID, REVOKED_AT);

            line.confirm(HOST_ID, REVOKED_AT.plusSeconds(60));

            assertThat(line.isConfirmed()).isTrue();
            assertThat(line.getConfirmRevokedAt()).isEqualTo(REVOKED_AT);
        }
    }

    // 배달비를 고쳐도 되는지 판단하는 재료
    @Nested
    @DisplayName("입금 흔적")
    class PaymentRecord {

        @Test
        @DisplayName("아무 일도 없던 참여자 줄은 흔적이 없다")
        void cleanLine() {
            assertThat(memberLine().hasPaymentRecord()).isFalse();
        }

        @Test
        @DisplayName("보냈어요를 눌렀으면 흔적이 있다")
        void marked() {
            Settlement line = memberLine();
            line.markPaid(MEMBER_ID, NOW);

            assertThat(line.hasPaymentRecord()).isTrue();
        }

        @Test
        @DisplayName("표시 없이 방장이 확인했어도 흔적이 있다")
        void confirmedWithoutMark() {
            // 이체만 하고 누르는 걸 잊은 경우. 이미 돈이 갔다
            Settlement line = memberLine();
            line.confirm(HOST_ID, NOW);

            assertThat(line.hasPaymentRecord()).isTrue();
        }

        @Test
        @DisplayName("확인했다가 취소했어도 흔적이 있다")
        void revoked() {
            // 정산표를 지우면 취소한 흔적도 사라진다
            Settlement line = memberLine();
            line.confirm(HOST_ID, NOW);
            line.revokeConfirm(HOST_ID, NOW.plusSeconds(60));

            assertThat(line.getMarkedPaidAt()).isNull();
            assertThat(line.getConfirmedPaidAt()).isNull();
            assertThat(line.hasPaymentRecord()).isTrue();
        }

        @Test
        @DisplayName("방장 줄은 처음부터 채워진 시각이 있어도 흔적이 아니다")
        void ignoresHostLine() {
            // 방장 줄의 시각은 만들 때 채운 것이지 누가 누른 게 아니다
            assertThat(hostLine().hasPaymentRecord()).isFalse();
        }
    }

    // 표시와 확인은 정산중에만 받는다
    @Nested
    @DisplayName("방 상태")
    class RoomState {

        @Test
        @DisplayName("주문완료된 방에서는 보냈다고 표시할 수 없다")
        void rejectsMarkAfterOrdered() {
            Settlement line = memberLine();
            forceStatus(GroupOrderStatus.ORDERED);

            assertThatThrownBy(() -> line.markPaid(MEMBER_ID, NOW))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(line.getMarkedPaidAt()).isNull();
        }

        @Test
        @DisplayName("정산중 취소가 아닌 취소 방에서는 보냈다고 표시할 수 없다")
        void rejectsMarkAfterCanceled() {
            // 정산중에 취소된 방의 늦은 표시는 LateMark에서 본다
            Settlement line = memberLine();
            forceStatus(GroupOrderStatus.CANCELED);
            ReflectionTestUtils.setField(groupOrder, "cancelType", CancelType.HOST_WHILE_CLOSED);

            assertThatThrownBy(() -> line.markPaid(MEMBER_ID, NOW))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(line.getMarkedPaidAt()).isNull();
        }

        @Test
        @DisplayName("주문완료된 방에서는 확인할 수 없다")
        void rejectsConfirmAfterOrdered() {
            Settlement line = memberLine();
            forceStatus(GroupOrderStatus.ORDERED);

            assertThatThrownBy(() -> line.confirm(HOST_ID, NOW))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(line.isConfirmed()).isFalse();
        }

        @Test
        @DisplayName("취소된 방에서는 확인할 수 없다")
        void rejectsConfirmAfterCanceled() {
            // 취소된 방의 정산표는 환불 근거다. 취소 뒤에 바뀌면 안 된다
            Settlement line = memberLine();
            forceStatus(GroupOrderStatus.CANCELED);

            assertThatThrownBy(() -> line.confirm(HOST_ID, NOW))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(line.isConfirmed()).isFalse();
        }

        @Test
        @DisplayName("배달완료된 방에서도 확인할 수 없다")
        void rejectsConfirmAfterDelivered() {
            Settlement line = memberLine();
            forceStatus(GroupOrderStatus.DELIVERED);

            assertThatThrownBy(() -> line.confirm(HOST_ID, NOW))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ===== 여기부터 환불 =====

    static final String CANCEL_REASON = "가게가 문을 닫았어요";
    static final Instant LATER = NOW.plusSeconds(3_600);

    // 방장이 정산중에 방을 취소한다. 입금 기록은 그 전에 찍어둬야 한다
    void cancelWhileSettling() {
        groupOrder.cancelByHost(HOST_ID, CANCEL_REASON, NOW);
    }

    // 보냈어요만 있는 줄로 정산중 취소까지
    Settlement markedThenCanceled() {
        Settlement line = memberLine();
        line.markPaid(MEMBER_ID, NOW);
        cancelWhileSettling();
        return line;
    }

    // 확인까지 된 줄로 정산중 취소까지
    Settlement confirmedThenCanceled() {
        Settlement line = memberLine();
        line.markPaid(MEMBER_ID, NOW);
        line.confirm(HOST_ID, NOW.plusSeconds(60));
        cancelWhileSettling();
        return line;
    }

    @Nested
    @DisplayName("환불 대상")
    class RefundTarget {

        @Test
        @DisplayName("정산중에 취소된 방이면 참이다")
        void canceledWhileSettling() {
            cancelWhileSettling();

            assertThat(groupOrder.isCanceledWhileSettling()).isTrue();
        }

        @Test
        @DisplayName("정산중인 방은 아직 취소된 방이 아니다")
        void settlingIsNotCanceled() {
            assertThat(groupOrder.isCanceledWhileSettling()).isFalse();
        }

        @Test
        @DisplayName("마감 뒤에 취소된 방은 정산중 취소가 아니다")
        void canceledAfterClosedIsNot() {
            forceStatus(GroupOrderStatus.CANCELED);
            ReflectionTestUtils.setField(groupOrder, "cancelType", CancelType.HOST_WHILE_CLOSED);

            assertThat(groupOrder.isCanceledWhileSettling()).isFalse();
        }

        @Test
        @DisplayName("보냈어요만 있는 줄도 대상이다")
        void markedOnly() {
            // 돈이 왔는데 방장이 확인을 안 눌렀을 수 있다
            assertThat(markedThenCanceled().isRefundTarget()).isTrue();
        }

        @Test
        @DisplayName("확인된 줄은 대상이다")
        void confirmed() {
            assertThat(confirmedThenCanceled().isRefundTarget()).isTrue();
        }

        @Test
        @DisplayName("보냈어요 없이 확인만 된 줄도 대상이다")
        void confirmedWithoutMark() {
            Settlement line = memberLine();
            line.confirm(HOST_ID, NOW);
            cancelWhileSettling();

            assertThat(line.isRefundTarget()).isTrue();
        }

        @Test
        @DisplayName("아무것도 누르지 않은 줄은 대상이 아니다")
        void untouched() {
            Settlement line = memberLine();
            cancelWhileSettling();

            assertThat(line.isRefundTarget()).isFalse();
        }

        @Test
        @DisplayName("방장 줄은 시각이 채워져 있어도 대상이 아니다")
        void excludesHostLine() {
            // 방장 줄의 시각은 만들 때 채운 것이다. 자기에게 돌려줄 돈은 없다
            Settlement line = hostLine();
            cancelWhileSettling();

            assertThat(line.isRefundTarget()).isFalse();
        }

        @Test
        @DisplayName("정산중인 방의 줄은 보냈어요가 있어도 대상이 아니다")
        void notWhileSettling() {
            Settlement line = memberLine();
            line.markPaid(MEMBER_ID, NOW);

            assertThat(line.isRefundTarget()).isFalse();
        }

        @Test
        @DisplayName("주문완료된 방의 줄은 대상이 아니다")
        void notAfterOrdered() {
            Settlement line = memberLine();
            line.confirm(HOST_ID, NOW);
            forceStatus(GroupOrderStatus.ORDERED);

            assertThat(line.isRefundTarget()).isFalse();
        }
    }

    @Nested
    @DisplayName("취소 뒤 늦은 보냈어요")
    class LateMark {

        @Test
        @DisplayName("정산중에 취소된 방에서는 늦게라도 보냈어요를 누를 수 있고 대상이 된다")
        void marksAfterCancel() {
            // 취소 20초 전에 이체해서 버튼을 누를 틈이 없었던 경우
            Settlement line = memberLine();
            cancelWhileSettling();

            line.markPaid(MEMBER_ID, LATER);

            assertThat(line.getMarkedPaidAt()).isEqualTo(LATER);
            assertThat(line.isRefundTarget()).isTrue();
        }

        @Test
        @DisplayName("이미 보냈어요가 있으면 처음 시각이 남는다")
        void keepsFirstMark() {
            Settlement line = markedThenCanceled();

            line.markPaid(MEMBER_ID, LATER);

            assertThat(line.getMarkedPaidAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("남의 줄에는 누를 수 없다")
        void rejectsOther() {
            Settlement line = memberLine();
            cancelWhileSettling();

            assertThatThrownBy(() -> line.markPaid(OTHER_ID, LATER))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> line.markPaid(HOST_ID, LATER))
                    .isInstanceOf(ForbiddenException.class);

            assertThat(line.getMarkedPaidAt()).isNull();
        }

        @Test
        @DisplayName("확인된 줄에는 누를 필요가 없다")
        void rejectsConfirmed() {
            Settlement line = memberLine();
            line.confirm(HOST_ID, NOW);
            cancelWhileSettling();

            assertThatThrownBy(() -> line.markPaid(MEMBER_ID, LATER))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("확인은 여전히 얼어 있다")
        void confirmStaysFrozen() {
            // 늦은 보냈어요가 열렸다고 확인 쪽까지 열리지 않는다
            Settlement line = markedThenCanceled();

            assertThatThrownBy(() -> line.confirm(HOST_ID, LATER))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(line.isConfirmed()).isFalse();
        }

        @Test
        @DisplayName("확인 취소도 여전히 얼어 있다")
        void revokeStaysFrozen() {
            // 방장이 확인을 지울 수 있으면 환불 대상에서 스스로 빠질 수 있다
            Settlement line = confirmedThenCanceled();

            assertThatThrownBy(() -> line.revokeConfirm(HOST_ID, LATER))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(line.isConfirmed()).isTrue();
        }
    }

    @Nested
    @DisplayName("돌려줬어요")
    class SendRefund {

        @Test
        @DisplayName("방장이 확인된 줄에 누르면 시각이 남는다")
        void sendsOnConfirmed() {
            Settlement line = confirmedThenCanceled();

            line.sendRefund(HOST_ID, LATER);

            assertThat(line.getRefundSentAt()).isEqualTo(LATER);
            // 완료는 참여자가 정한다
            assertThat(line.getRefundReceivedAt()).isNull();
        }

        @Test
        @DisplayName("보냈어요만 있는 줄에도 누를 수 있다")
        void sendsOnMarkedOnly() {
            Settlement line = markedThenCanceled();

            line.sendRefund(HOST_ID, LATER);

            assertThat(line.getRefundSentAt()).isEqualTo(LATER);
        }

        @Test
        @DisplayName("두 번 눌러도 처음 시각이 남는다")
        void keepsFirstTime() {
            Settlement line = confirmedThenCanceled();
            line.sendRefund(HOST_ID, LATER);

            line.sendRefund(HOST_ID, LATER.plusSeconds(60));

            assertThat(line.getRefundSentAt()).isEqualTo(LATER);
        }

        @Test
        @DisplayName("반박한 줄에도 누를 수 있다")
        void sendsAfterDenial() {
            // 반박한 뒤 통장을 다시 보니 돈이 있었다
            Settlement line = markedThenCanceled();
            line.denyPayment(HOST_ID, LATER);

            line.sendRefund(HOST_ID, LATER.plusSeconds(60));

            assertThat(line.getRefundSentAt()).isEqualTo(LATER.plusSeconds(60));
        }

        @Test
        @DisplayName("참여자는 누를 수 없다")
        void rejectsMember() {
            Settlement line = confirmedThenCanceled();

            assertThatThrownBy(() -> line.sendRefund(MEMBER_ID, LATER))
                    .isInstanceOf(ForbiddenException.class);

            assertThat(line.getRefundSentAt()).isNull();
        }

        @Test
        @DisplayName("대상이 아닌 줄에는 누를 수 없다")
        void rejectsNonTarget() {
            Settlement line = memberLine();
            cancelWhileSettling();

            assertThatThrownBy(() -> line.sendRefund(HOST_ID, LATER))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(line.getRefundSentAt()).isNull();
        }

        @Test
        @DisplayName("방장 자기 줄에는 누를 수 없다")
        void rejectsHostLine() {
            Settlement line = hostLine();
            cancelWhileSettling();

            assertThatThrownBy(() -> line.sendRefund(HOST_ID, LATER))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("정산중인 방에서는 누를 수 없다")
        void rejectsWhileSettling() {
            Settlement line = memberLine();
            line.confirm(HOST_ID, NOW);

            assertThatThrownBy(() -> line.sendRefund(HOST_ID, LATER))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(line.getRefundSentAt()).isNull();
        }

        @Test
        @DisplayName("이미 돌려받은 줄에는 누를 수 없다")
        void rejectsAfterReceived() {
            Settlement line = confirmedThenCanceled();
            line.receiveRefund(MEMBER_ID, LATER);

            assertThatThrownBy(() -> line.sendRefund(HOST_ID, LATER.plusSeconds(60)))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(line.getRefundSentAt()).isNull();
        }
    }

    @Nested
    @DisplayName("입금 안 됐어요")
    class DenyPayment {

        @Test
        @DisplayName("방장이 보냈어요만 있는 줄에 누르면 시각이 남는다")
        void denies() {
            Settlement line = markedThenCanceled();

            line.denyPayment(HOST_ID, LATER);

            assertThat(line.getPaymentDeniedAt()).isEqualTo(LATER);
            // 보냈어요는 참여자의 주장이라 지우지 않는다
            assertThat(line.getMarkedPaidAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("두 번 눌러도 처음 시각이 남는다")
        void keepsFirstTime() {
            Settlement line = markedThenCanceled();
            line.denyPayment(HOST_ID, LATER);

            line.denyPayment(HOST_ID, LATER.plusSeconds(60));

            assertThat(line.getPaymentDeniedAt()).isEqualTo(LATER);
        }

        @Test
        @DisplayName("확인된 줄은 반박할 수 없다")
        void rejectsConfirmed() {
            // 확인해놓고 안 왔다고 하면 확인이 무의미해진다
            Settlement line = confirmedThenCanceled();

            assertThatThrownBy(() -> line.denyPayment(HOST_ID, LATER))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(line.getPaymentDeniedAt()).isNull();
        }

        @Test
        @DisplayName("대상이 아닌 줄은 반박할 것이 없다")
        void rejectsNonTarget() {
            Settlement line = memberLine();
            cancelWhileSettling();

            assertThatThrownBy(() -> line.denyPayment(HOST_ID, LATER))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("참여자는 누를 수 없다")
        void rejectsMember() {
            Settlement line = markedThenCanceled();

            assertThatThrownBy(() -> line.denyPayment(MEMBER_ID, LATER))
                    .isInstanceOf(ForbiddenException.class);

            assertThat(line.getPaymentDeniedAt()).isNull();
        }

        @Test
        @DisplayName("돌려줬어요 뒤에는 반박할 수 없다")
        void rejectsAfterSent() {
            // 돌려줘 놓고 돈을 안 받았다고 하는 건 모순이다
            Settlement line = markedThenCanceled();
            line.sendRefund(HOST_ID, LATER);

            assertThatThrownBy(() -> line.denyPayment(HOST_ID, LATER.plusSeconds(60)))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(line.getPaymentDeniedAt()).isNull();
        }

        @Test
        @DisplayName("이미 돌려받은 줄은 반박할 수 없다")
        void rejectsAfterReceived() {
            Settlement line = markedThenCanceled();
            line.receiveRefund(MEMBER_ID, LATER);

            assertThatThrownBy(() -> line.denyPayment(HOST_ID, LATER.plusSeconds(60)))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("정산중인 방에서는 누를 수 없다")
        void rejectsWhileSettling() {
            // 정산중에는 그냥 확인을 안 누르면 된다
            Settlement line = memberLine();
            line.markPaid(MEMBER_ID, NOW);

            assertThatThrownBy(() -> line.denyPayment(HOST_ID, LATER))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("돌려받았어요")
    class ReceiveRefund {

        @Test
        @DisplayName("방장이 돌려줬다고 한 뒤 참여자가 누르면 끝난다")
        void receives() {
            Settlement line = confirmedThenCanceled();
            line.sendRefund(HOST_ID, LATER);

            line.receiveRefund(MEMBER_ID, LATER.plusSeconds(60));

            assertThat(line.getRefundReceivedAt()).isEqualTo(LATER.plusSeconds(60));
        }

        @Test
        @DisplayName("방장이 돌려줬어요를 안 눌렀어도 누를 수 있다")
        void receivesWithoutSent() {
            // 방장이 이체만 하고 버튼을 잊었다
            Settlement line = confirmedThenCanceled();

            line.receiveRefund(MEMBER_ID, LATER);

            assertThat(line.getRefundReceivedAt()).isEqualTo(LATER);
            assertThat(line.getRefundSentAt()).isNull();
        }

        @Test
        @DisplayName("반박된 줄에서도 누를 수 있다")
        void receivesAfterDenial() {
            // 받았다는 말이 반박보다 우선한다
            Settlement line = markedThenCanceled();
            line.denyPayment(HOST_ID, LATER);

            line.receiveRefund(MEMBER_ID, LATER.plusSeconds(60));

            assertThat(line.getRefundReceivedAt()).isEqualTo(LATER.plusSeconds(60));
        }

        @Test
        @DisplayName("두 번 눌러도 처음 시각이 남는다")
        void keepsFirstTime() {
            Settlement line = confirmedThenCanceled();
            line.receiveRefund(MEMBER_ID, LATER);

            line.receiveRefund(MEMBER_ID, LATER.plusSeconds(60));

            assertThat(line.getRefundReceivedAt()).isEqualTo(LATER);
        }

        @Test
        @DisplayName("방장이 대신 누를 수 없다")
        void rejectsHost() {
            // 이걸 허용하면 방장이 돈을 안 돌려주고 끝낼 수 있다
            Settlement line = confirmedThenCanceled();

            assertThatThrownBy(() -> line.receiveRefund(HOST_ID, LATER))
                    .isInstanceOf(ForbiddenException.class);

            assertThat(line.getRefundReceivedAt()).isNull();
        }

        @Test
        @DisplayName("남의 줄에는 누를 수 없다")
        void rejectsOther() {
            Settlement line = confirmedThenCanceled();

            assertThatThrownBy(() -> line.receiveRefund(OTHER_ID, LATER))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        @DisplayName("대상이 아닌 줄에는 누를 수 없다")
        void rejectsNonTarget() {
            Settlement line = memberLine();
            cancelWhileSettling();

            assertThatThrownBy(() -> line.receiveRefund(MEMBER_ID, LATER))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(line.getRefundReceivedAt()).isNull();
        }

        @Test
        @DisplayName("방장 자기 줄에는 누를 수 없다")
        void rejectsHostLine() {
            Settlement line = hostLine();
            cancelWhileSettling();

            assertThatThrownBy(() -> line.receiveRefund(HOST_ID, LATER))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("정산중인 방에서는 누를 수 없다")
        void rejectsWhileSettling() {
            Settlement line = memberLine();
            line.confirm(HOST_ID, NOW);

            assertThatThrownBy(() -> line.receiveRefund(MEMBER_ID, LATER))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("환불 상태")
    class Status {

        @Test
        @DisplayName("대상이 아니면 null이다")
        void nullForNonTarget() {
            Settlement untouched = memberLine();
            Settlement host = hostLine();
            cancelWhileSettling();

            assertThat(untouched.refundStatus()).isNull();
            assertThat(host.refundStatus()).isNull();
        }

        @Test
        @DisplayName("정산중인 방의 줄은 null이다")
        void nullWhileSettling() {
            Settlement line = memberLine();
            line.confirm(HOST_ID, NOW);

            assertThat(line.refundStatus()).isNull();
        }

        @Test
        @DisplayName("대상인데 아무 일도 없으면 대기다")
        void pending() {
            assertThat(confirmedThenCanceled().refundStatus()).isEqualTo(RefundStatus.REFUND_PENDING);
        }

        @Test
        @DisplayName("반박하면 반박이다")
        void denied() {
            Settlement line = markedThenCanceled();
            line.denyPayment(HOST_ID, LATER);

            assertThat(line.refundStatus()).isEqualTo(RefundStatus.PAYMENT_DENIED);
        }

        @Test
        @DisplayName("돌려줬다고 하면 돌려줌이다")
        void sent() {
            Settlement line = confirmedThenCanceled();
            line.sendRefund(HOST_ID, LATER);

            assertThat(line.refundStatus()).isEqualTo(RefundStatus.REFUND_SENT);
        }

        @Test
        @DisplayName("반박한 뒤 돌려줬다고 하면 돌려줌이다")
        void sentAfterDenial() {
            // 두 칸이 같이 있으면 돌려줬어요가 늘 나중이다. 방장이 생각을 바꿨다
            Settlement line = markedThenCanceled();
            line.denyPayment(HOST_ID, LATER);
            line.sendRefund(HOST_ID, LATER.plusSeconds(60));

            assertThat(line.refundStatus()).isEqualTo(RefundStatus.REFUND_SENT);
        }

        @Test
        @DisplayName("돌려받았다고 하면 완료다")
        void refunded() {
            Settlement line = confirmedThenCanceled();
            line.sendRefund(HOST_ID, LATER);
            line.receiveRefund(MEMBER_ID, LATER.plusSeconds(60));

            assertThat(line.refundStatus()).isEqualTo(RefundStatus.REFUNDED);
        }

        @Test
        @DisplayName("반박된 줄이라도 돌려받았다고 하면 완료다")
        void refundedAfterDenial() {
            Settlement line = markedThenCanceled();
            line.denyPayment(HOST_ID, LATER);
            line.receiveRefund(MEMBER_ID, LATER.plusSeconds(60));

            assertThat(line.refundStatus()).isEqualTo(RefundStatus.REFUNDED);
        }
    }

    // 방장이 24시간 주문하지 않아 스케줄러가 취소한 방
    @Nested
    @DisplayName("자동 취소된 정산중 방의 환불")
    class RefundAfterTimeout {

        // setUp에서 NOW에 정산을 시작했다
        static final Instant EXPIRED_AT = NOW.plus(GroupOrder.SETTLING_EXPIRY);

        @Test
        @DisplayName("확인된 줄은 환불 대기가 되고 참여자가 돌려받았어요를 누를 수 있다")
        void confirmedLineIsRefundable() {
            Settlement line = memberLine();
            line.confirm(HOST_ID, NOW.plusSeconds(60));

            groupOrder.expireIfStale(EXPIRED_AT);

            assertThat(line.refundStatus()).isEqualTo(RefundStatus.REFUND_PENDING);
            line.receiveRefund(MEMBER_ID, EXPIRED_AT.plusSeconds(60));
            assertThat(line.refundStatus()).isEqualTo(RefundStatus.REFUNDED);
        }

        @Test
        @DisplayName("늦은 보냈어요를 누를 수 있다")
        void lateMark() {
            Settlement line = memberLine();
            groupOrder.expireIfStale(EXPIRED_AT);

            line.markPaid(MEMBER_ID, EXPIRED_AT.plusSeconds(60));

            assertThat(line.isRefundTarget()).isTrue();
        }

        @Test
        @DisplayName("돌아온 방장이 돌려줬어요를 누를 수 있다")
        void hostCanSendRefund() {
            Settlement line = memberLine();
            line.markPaid(MEMBER_ID, NOW.plusSeconds(30));
            groupOrder.expireIfStale(EXPIRED_AT);

            line.sendRefund(HOST_ID, EXPIRED_AT.plus(java.time.Duration.ofDays(1)));

            assertThat(line.refundStatus()).isEqualTo(RefundStatus.REFUND_SENT);
        }
    }
}
