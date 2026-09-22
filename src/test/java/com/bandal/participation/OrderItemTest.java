package com.bandal.participation;

import com.bandal.grouporder.GroupOrder;
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

// DB 없이 메뉴를 담고 빼는 규칙만 확인한다.
class OrderItemTest {

    static final Long HOST_ID = 1_000L;
    static final Long MEMBER_ID = 1_001L;
    static final Long OTHER_ID = 1_002L;
    static final long MIN_ORDER_AMOUNT = 15_000;
    static final Instant DEADLINE = Instant.parse("2026-09-22T10:30:00Z");
    static final Instant JOINED_AT = Instant.parse("2026-09-22T09:00:00Z");

    GroupOrder groupOrder;
    Participation participation;
    Participation otherParticipation;

    @BeforeEach
    void setUp() {
        University university = new University("한국대학교", "hankuk.ac.kr");
        PickupSpot pickupSpot = new PickupSpot(university, "제1기숙사 로비", null);
        User host = new User(university, "kim@hankuk.ac.kr", "hashed-password", "배고파");
        User member = new User(university, "lee@hankuk.ac.kr", "hashed-password", "마라탕러버");
        User other = new User(university, "park@hankuk.ac.kr", "hashed-password", "꿔바로우");

        // 저장하지 않은 엔티티라 id가 null이다. 주인 확인을 테스트하려고 id만 직접 넣는다.
        // 값만 같은 새 Long을 넣어서 요청 id와 다른 객체가 되게 한다.
        ReflectionTestUtils.setField(host, "id", Long.valueOf(HOST_ID.longValue()));
        ReflectionTestUtils.setField(member, "id", Long.valueOf(MEMBER_ID.longValue()));
        ReflectionTestUtils.setField(other, "id", Long.valueOf(OTHER_ID.longValue()));

        groupOrder = new GroupOrder(host, pickupSpot, "○○마라탕", MIN_ORDER_AMOUNT, DEADLINE, 4);

        participation = new Participation(groupOrder, member, JOINED_AT);
        otherParticipation = new Participation(groupOrder, other, JOINED_AT);
        ReflectionTestUtils.setField(participation, "id", 2_000L);
        ReflectionTestUtils.setField(otherParticipation, "id", 2_001L);
    }

    @Nested
    @DisplayName("메뉴 담기")
    class AddItem {

        @Test
        @DisplayName("적은 그대로 담기고 줄 금액은 단가 곱하기 개수다")
        void addsItem() {
            OrderItem item = participation.addItem(MEMBER_ID, "마라탕", "2단계, 꿔바로우 추가", 12_000, 2);

            assertThat(item.getParticipation()).isSameAs(participation);
            assertThat(item.getMenuName()).isEqualTo("마라탕");
            assertThat(item.getOptions()).isEqualTo("2단계, 꿔바로우 추가");
            assertThat(item.getUnitPrice()).isEqualTo(12_000);
            assertThat(item.getQuantity()).isEqualTo(2);
            assertThat(item.getAmount()).isEqualTo(24_000);
        }

        @Test
        @DisplayName("옵션이 없는 메뉴도 담을 수 있다")
        void allowsNullOptions() {
            OrderItem item = participation.addItem(MEMBER_ID, "공기밥", null, 1_000, 1);

            assertThat(item.getOptions()).isNull();
            assertThat(item.getAmount()).isEqualTo(1_000);
        }

        @Test
        @DisplayName("공백만 적은 옵션은 없는 것으로 저장한다")
        void blankOptionsBecomeNull() {
            OrderItem item = participation.addItem(MEMBER_ID, "공기밥", "   ", 1_000, 1);

            assertThat(item.getOptions()).isNull();
        }

        @Test
        @DisplayName("같은 메뉴를 옵션만 다르게 여러 줄 담을 수 있다")
        void allowsSameMenuWithDifferentOptions() {
            OrderItem mild = participation.addItem(MEMBER_ID, "마라탕", "1단계", 9_000, 1);
            OrderItem hot = participation.addItem(MEMBER_ID, "마라탕", "3단계", 9_000, 1);

            assertThat(mild.getOptions()).isEqualTo("1단계");
            assertThat(hot.getOptions()).isEqualTo("3단계");
        }

        @Test
        @DisplayName("남의 참여에는 메뉴를 담을 수 없다")
        void rejectsOtherUser() {
            assertThatThrownBy(() -> participation.addItem(OTHER_ID, "마라탕", null, 9_000, 1))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("방장이어도 남의 참여에는 메뉴를 담을 수 없다")
        void rejectsHostOfOtherParticipation() {
            assertThatThrownBy(() -> participation.addItem(HOST_ID, "마라탕", null, 9_000, 1))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("마감된 방에는 메뉴를 담을 수 없다")
        void rejectsWhenClosed() {
            groupOrder.closeByHost(HOST_ID, 2, 20_000);

            assertThatThrownBy(() -> participation.addItem(MEMBER_ID, "마라탕", null, 9_000, 1))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("취소된 방에는 메뉴를 담을 수 없다")
        void rejectsWhenCanceled() {
            groupOrder.cancelByHost(HOST_ID, null);

            assertThatThrownBy(() -> participation.addItem(MEMBER_ID, "마라탕", null, 9_000, 1))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("메뉴 이름이 없으면 담을 수 없다")
        void rejectsMissingMenuName() {
            assertThatThrownBy(() -> participation.addItem(MEMBER_ID, null, null, 9_000, 1))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> participation.addItem(MEMBER_ID, "   ", null, 9_000, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("가격이 0원 이하면 담을 수 없다")
        void rejectsNonPositivePrice() {
            assertThatThrownBy(() -> participation.addItem(MEMBER_ID, "마라탕", null, 0, 1))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> participation.addItem(MEMBER_ID, "마라탕", null, -1, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("개수가 1보다 작으면 담을 수 없다")
        void rejectsNonPositiveQuantity() {
            assertThatThrownBy(() -> participation.addItem(MEMBER_ID, "마라탕", null, 9_000, 0))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> participation.addItem(MEMBER_ID, "마라탕", null, 9_000, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("참여 없이 메뉴만 따로 만들 수 없다")
        void rejectsNullParticipation() {
            assertThatThrownBy(() -> new OrderItem(null, "마라탕", null, 9_000, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("메뉴 빼기")
    class RemoveItem {

        OrderItem item;

        @BeforeEach
        void addOne() {
            item = participation.addItem(MEMBER_ID, "마라탕", "2단계", 9_000, 1);
        }

        @Test
        @DisplayName("모집중에 본인 메뉴는 뺄 수 있다")
        void removes() {
            assertThatCode(() -> participation.removeItem(MEMBER_ID, item))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("남은 내 메뉴를 뺄 수 없다")
        void rejectsOtherUser() {
            assertThatThrownBy(() -> participation.removeItem(OTHER_ID, item))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("마감된 방에서는 뺄 수 없다")
        void rejectsWhenClosed() {
            groupOrder.closeByHost(HOST_ID, 2, 20_000);

            assertThatThrownBy(() -> participation.removeItem(MEMBER_ID, item))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("다른 참여의 메뉴는 뺄 수 없다")
        void rejectsItemOfOtherParticipation() {
            OrderItem otherItem = otherParticipation.addItem(OTHER_ID, "꿔바로우", null, 12_000, 1);

            assertThatThrownBy(() -> participation.removeItem(MEMBER_ID, otherItem))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
