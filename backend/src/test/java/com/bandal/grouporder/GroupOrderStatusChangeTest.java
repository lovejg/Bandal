package com.bandal.grouporder;

import com.bandal.pickupspot.PickupSpot;
import com.bandal.university.University;
import com.bandal.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 상태가 바뀔 때마다 기록이 정확히 하나 남는가. DB 없이 엔티티만 본다
class GroupOrderStatusChangeTest {

    static final Long ROOM_ID = 3L;
    static final Long HOST_ID = 1_000L;
    static final long MIN_ORDER_AMOUNT = 15_000;
    static final Instant DEADLINE = Instant.parse("2026-09-17T10:30:00Z");
    static final Instant BEFORE_DEADLINE = DEADLINE.minusSeconds(60);
    static final Instant AFTER_DEADLINE = DEADLINE.plusSeconds(60);

    GroupOrder groupOrder;

    @BeforeEach
    void setUp() {
        University university = new University("한국대학교", "hankuk.ac.kr");
        ReflectionTestUtils.setField(university, "id", 10L);
        PickupSpot pickupSpot = new PickupSpot(university, "제1기숙사 로비", null);
        User host = new User(university, "kim@hankuk.ac.kr", "hashed-password", "배고파");
        host.registerAccount("한국은행", "110-123-456789", "김민수");
        ReflectionTestUtils.setField(host, "id", HOST_ID);

        groupOrder = new GroupOrder(host, pickupSpot, "○○마라탕", MIN_ORDER_AMOUNT, DEADLINE, 4);
        // 사건에 방 id가 들어가는지 보려고 저장된 방처럼 id를 넣는다
        ReflectionTestUtils.setField(groupOrder, "id", ROOM_ID);
    }

    // 마감까지. 기록 1개
    void toClosed() {
        groupOrder.closeByHost(HOST_ID, 2, MIN_ORDER_AMOUNT, BEFORE_DEADLINE);
    }

    // 정산중까지. 기록 2개
    void toSettling(Instant now) {
        toClosed();
        groupOrder.startSettlement(HOST_ID, 3_000, now);
    }

    // 주문완료까지. 기록 3개
    void toOrdered(Instant now) {
        toSettling(BEFORE_DEADLINE);
        groupOrder.order(HOST_ID, null, now);
    }

    GroupOrderStatusChanged last() {
        List<GroupOrderStatusChanged> changes = groupOrder.statusChanges();
        return changes.get(changes.size() - 1);
    }

    @Test
    @DisplayName("막 만든 방은 기록이 없다. 모집중은 바뀐 게 아니라 시작 상태다")
    void newRoomHasNoChange() {
        assertThat(groupOrder.statusChanges()).isEmpty();
    }

    @Nested
    @DisplayName("상태가 바뀌면 기록이 하나 남는다")
    class Recorded {

        @Test
        @DisplayName("방장이 마감: 모집중 → 마감. 방 id와 시각도 담긴다")
        void closeByHost() {
            toClosed();

            assertThat(groupOrder.statusChanges()).containsExactly(new GroupOrderStatusChanged(
                    ROOM_ID, GroupOrderStatus.RECRUITING, GroupOrderStatus.CLOSED, StatusChangedBy.HOST, BEFORE_DEADLINE));
        }

        @Test
        @DisplayName("방장이 마감 시각 뒤에 눌러 조건 미달로 취소돼도 기록은 하나다. 마감 시각 규칙이 바꾼 것이라 시스템")
        void closeByHostAfterDeadlineCanceledOnce() {
            // closeByHost가 closeAtDeadline으로 넘긴다. 두 번 기록되면 안 된다
            groupOrder.closeByHost(HOST_ID, 1, 0, AFTER_DEADLINE);

            assertThat(groupOrder.statusChanges()).containsExactly(new GroupOrderStatusChanged(
                    ROOM_ID, GroupOrderStatus.RECRUITING, GroupOrderStatus.CANCELED, StatusChangedBy.SYSTEM, AFTER_DEADLINE));
        }

        @Test
        @DisplayName("마감 시각에 조건을 채웠으면: 모집중 → 마감")
        void closeAtDeadline() {
            groupOrder.closeAtDeadline(2, MIN_ORDER_AMOUNT, AFTER_DEADLINE);

            assertThat(groupOrder.statusChanges()).containsExactly(new GroupOrderStatusChanged(
                    ROOM_ID, GroupOrderStatus.RECRUITING, GroupOrderStatus.CLOSED, StatusChangedBy.SYSTEM, AFTER_DEADLINE));
        }

        @Test
        @DisplayName("마감 시각에 인원이 모자라면: 모집중 → 취소")
        void closeAtDeadlineCanceled() {
            groupOrder.closeAtDeadline(1, MIN_ORDER_AMOUNT, AFTER_DEADLINE);

            assertThat(groupOrder.statusChanges()).containsExactly(new GroupOrderStatusChanged(
                    ROOM_ID, GroupOrderStatus.RECRUITING, GroupOrderStatus.CANCELED, StatusChangedBy.SYSTEM, AFTER_DEADLINE));
        }

        @Test
        @DisplayName("방장이 모집중에 취소: 모집중 → 취소")
        void cancelWhileRecruiting() {
            groupOrder.cancelByHost(HOST_ID, null, BEFORE_DEADLINE);

            assertThat(groupOrder.statusChanges()).containsExactly(new GroupOrderStatusChanged(
                    ROOM_ID, GroupOrderStatus.RECRUITING, GroupOrderStatus.CANCELED, StatusChangedBy.HOST, BEFORE_DEADLINE));
        }

        @Test
        @DisplayName("방장이 마감 뒤에 취소: 마감 → 취소")
        void cancelWhileClosed() {
            toClosed();
            Instant now = BEFORE_DEADLINE.plusSeconds(10);

            groupOrder.cancelByHost(HOST_ID, "가게가 문을 닫았어요", now);

            assertThat(groupOrder.statusChanges()).hasSize(2);
            assertThat(last()).isEqualTo(new GroupOrderStatusChanged(
                    ROOM_ID, GroupOrderStatus.CLOSED, GroupOrderStatus.CANCELED, StatusChangedBy.HOST, now));
        }

        @Test
        @DisplayName("방장이 정산중에 취소: 정산중 → 취소")
        void cancelWhileSettling() {
            toSettling(BEFORE_DEADLINE);
            Instant now = BEFORE_DEADLINE.plusSeconds(10);

            groupOrder.cancelByHost(HOST_ID, "가게가 문을 닫았어요", now);

            assertThat(groupOrder.statusChanges()).hasSize(3);
            assertThat(last()).isEqualTo(new GroupOrderStatusChanged(
                    ROOM_ID, GroupOrderStatus.SETTLING, GroupOrderStatus.CANCELED, StatusChangedBy.HOST, now));
        }

        @Test
        @DisplayName("정산 시작: 마감 → 정산중")
        void startSettlement() {
            Instant now = BEFORE_DEADLINE.plusSeconds(10);

            toSettling(now);

            assertThat(groupOrder.statusChanges()).hasSize(2);
            assertThat(last()).isEqualTo(new GroupOrderStatusChanged(
                    ROOM_ID, GroupOrderStatus.CLOSED, GroupOrderStatus.SETTLING, StatusChangedBy.HOST, now));
        }

        @Test
        @DisplayName("주문완료: 정산중 → 주문완료")
        void order() {
            Instant now = BEFORE_DEADLINE.plusSeconds(10);

            toOrdered(now);

            assertThat(groupOrder.statusChanges()).hasSize(3);
            assertThat(last()).isEqualTo(new GroupOrderStatusChanged(
                    ROOM_ID, GroupOrderStatus.SETTLING, GroupOrderStatus.ORDERED, StatusChangedBy.HOST, now));
        }

        @Test
        @DisplayName("배달완료: 주문완료 → 배달완료")
        void deliver() {
            toOrdered(BEFORE_DEADLINE);
            Instant now = BEFORE_DEADLINE.plusSeconds(10);

            groupOrder.deliver(HOST_ID, now);

            assertThat(groupOrder.statusChanges()).hasSize(4);
            assertThat(last()).isEqualTo(new GroupOrderStatusChanged(
                    ROOM_ID, GroupOrderStatus.ORDERED, GroupOrderStatus.DELIVERED, StatusChangedBy.HOST, now));
        }
    }

    @Nested
    @DisplayName("스케줄러가 넘길 때도 기록이 남는다")
    class Expired {

        @Test
        @DisplayName("마감에서 오래 멈춤: 마감 → 취소")
        void closedTimeout() {
            toClosed();
            Instant now = BEFORE_DEADLINE.plus(GroupOrder.CLOSED_EXPIRY);

            groupOrder.expireIfStale(now);

            assertThat(groupOrder.statusChanges()).hasSize(2);
            assertThat(last()).isEqualTo(new GroupOrderStatusChanged(
                    ROOM_ID, GroupOrderStatus.CLOSED, GroupOrderStatus.CANCELED, StatusChangedBy.SYSTEM, now));
        }

        @Test
        @DisplayName("정산중에서 오래 멈춤: 정산중 → 취소")
        void settlingTimeout() {
            toSettling(BEFORE_DEADLINE);
            Instant now = BEFORE_DEADLINE.plus(GroupOrder.SETTLING_EXPIRY);

            groupOrder.expireIfStale(now);

            assertThat(groupOrder.statusChanges()).hasSize(3);
            assertThat(last()).isEqualTo(new GroupOrderStatusChanged(
                    ROOM_ID, GroupOrderStatus.SETTLING, GroupOrderStatus.CANCELED, StatusChangedBy.SYSTEM, now));
        }

        @Test
        @DisplayName("주문완료에서 오래 멈춤: 주문완료 → 배달완료")
        void orderedTimeout() {
            toOrdered(BEFORE_DEADLINE);
            Instant now = BEFORE_DEADLINE.plus(GroupOrder.ORDERED_EXPIRY);

            groupOrder.expireIfStale(now);

            assertThat(groupOrder.statusChanges()).hasSize(4);
            assertThat(last()).isEqualTo(new GroupOrderStatusChanged(
                    ROOM_ID, GroupOrderStatus.ORDERED, GroupOrderStatus.DELIVERED, StatusChangedBy.SYSTEM, now));
        }

        @Test
        @DisplayName("기간이 덜 찼으면 아무것도 안 바뀌고 기록도 없다")
        void notStaleYet() {
            toClosed();

            groupOrder.expireIfStale(BEFORE_DEADLINE.plus(GroupOrder.CLOSED_EXPIRY).minus(Duration.ofMinutes(1)));

            assertThat(groupOrder.statusChanges()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("상태가 안 바뀌면 기록도 없다")
    class NotRecorded {

        @Test
        @DisplayName("규칙에 걸려 거절되면 기록이 없다")
        void rejected() {
            // 1명뿐이라 마감할 수 없다
            assertThatThrownBy(() -> groupOrder.closeByHost(HOST_ID, 1, MIN_ORDER_AMOUNT, BEFORE_DEADLINE))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(groupOrder.statusChanges()).isEmpty();
        }

        @Test
        @DisplayName("같은 배달비로 고치면 바뀐 게 없어 기록이 없다")
        void sameDeliveryFee() {
            toSettling(BEFORE_DEADLINE);

            // 정산 시작 때와 같은 3,000원
            groupOrder.changeDeliveryFee(HOST_ID, 3_000, BEFORE_DEADLINE);

            assertThat(groupOrder.statusChanges()).hasSize(2);
        }

        @Test
        @DisplayName("방장이 아니라 배달비 수정이 거절되면 기록이 없다")
        void rejectedDeliveryFee() {
            toSettling(BEFORE_DEADLINE);

            assertThatThrownBy(() -> groupOrder.changeDeliveryFee(HOST_ID + 1, 4_000, BEFORE_DEADLINE))
                    .isInstanceOf(RuntimeException.class);

            assertThat(groupOrder.statusChanges()).hasSize(2);
        }
    }

    @Test
    @DisplayName("배달비 수정: 상태는 그대로(정산중 → 정산중)이고 종류가 배달비 수정인 기록이 하나 더 남는다")
    void changeDeliveryFee() {
        toSettling(BEFORE_DEADLINE);
        Instant changedAt = BEFORE_DEADLINE.plusSeconds(120);

        groupOrder.changeDeliveryFee(HOST_ID, 4_000, changedAt);

        assertThat(groupOrder.statusChanges()).hasSize(3);
        assertThat(last()).isEqualTo(new GroupOrderStatusChanged(ROOM_ID, GroupOrderStatus.SETTLING, GroupOrderStatus.SETTLING,
                StatusChangedBy.HOST, GroupOrderChangeKind.DELIVERY_FEE_CHANGED, changedAt));
    }

    @Test
    @DisplayName("내보낸 뒤 비우면 다음에는 새 기록만 나간다")
    void clearedAfterPublication() {
        toClosed();
        // save 때 Spring이 내보내고 나서 부르는 메서드
        groupOrder.clearStatusChanges();

        groupOrder.startSettlement(HOST_ID, 3_000, BEFORE_DEADLINE);

        assertThat(groupOrder.statusChanges()).containsExactly(new GroupOrderStatusChanged(
                ROOM_ID, GroupOrderStatus.CLOSED, GroupOrderStatus.SETTLING, StatusChangedBy.HOST, BEFORE_DEADLINE));
    }
}
