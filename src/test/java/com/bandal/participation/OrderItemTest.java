package com.bandal.participation;

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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// DB 없이 메뉴를 담고, 고치고, 빼는 규칙만 확인한다.
class OrderItemTest {

    static final Long HOST_ID = 1_000L;
    static final Long MEMBER_ID = 1_001L;
    static final Long OTHER_ID = 1_002L;
    static final long MIN_ORDER_AMOUNT = 15_000;
    static final Instant DEADLINE = Instant.parse("2026-09-22T10:30:00Z");
    static final Instant JOINED_AT = Instant.parse("2026-09-22T09:00:00Z");
    // 마감 1분 전. 담기와 고치기가 열려 있는 때
    static final Instant OPEN = DEADLINE.minusSeconds(60);
    // 마감 1분 뒤. 스케줄러가 없어서 상태는 아직 모집중일 수 있다
    static final Instant LATE = DEADLINE.plusSeconds(60);

    GroupOrder groupOrder;
    Participation hostParticipation;
    Participation participation;
    Participation otherParticipation;

    @BeforeEach
    void setUp() {
        University university = new University("한국대학교", "hankuk.ac.kr");
        PickupSpot pickupSpot = new PickupSpot(university, "제1기숙사 로비", null);
        User host = new User(university, "kim@hankuk.ac.kr", "hashed-password", "배고파");
        // 계좌가 없으면 방을 만들 수 없다 (ADR-031)
        host.registerAccount("한국은행", "110-123-456789", "김민수");
        User member = new User(university, "lee@hankuk.ac.kr", "hashed-password", "마라탕러버");
        User other = new User(university, "park@hankuk.ac.kr", "hashed-password", "꿔바로우");

        // 저장하지 않은 엔티티라 id가 null이다. 주인 확인을 테스트하려고 id만 직접 넣는다.
        // 값만 같은 새 Long을 넣어서 요청 id와 다른 객체가 되게 한다.
        ReflectionTestUtils.setField(host, "id", Long.valueOf(HOST_ID.longValue()));
        ReflectionTestUtils.setField(member, "id", Long.valueOf(MEMBER_ID.longValue()));
        ReflectionTestUtils.setField(other, "id", Long.valueOf(OTHER_ID.longValue()));

        groupOrder = new GroupOrder(host, pickupSpot, "○○마라탕", MIN_ORDER_AMOUNT, DEADLINE, 4);

        hostParticipation = new Participation(groupOrder, host, JOINED_AT);
        participation = new Participation(groupOrder, member, JOINED_AT);
        otherParticipation = new Participation(groupOrder, other, JOINED_AT);
        ReflectionTestUtils.setField(hostParticipation, "id", 1_999L);
        ReflectionTestUtils.setField(participation, "id", 2_000L);
        ReflectionTestUtils.setField(otherParticipation, "id", 2_001L);
    }

    @Nested
    @DisplayName("메뉴 담기")
    class AddItem {

        @Test
        @DisplayName("적은 그대로 담기고 줄 금액은 단가 곱하기 개수다")
        void addsItem() {
            OrderItem item = participation.addItem(MEMBER_ID, "마라탕", "2단계, 꿔바로우 추가", 12_000, 2, OPEN);

            assertThat(item.getParticipation()).isSameAs(participation);
            assertThat(item.getMenuName()).isEqualTo("마라탕");
            assertThat(item.getOptions()).isEqualTo("2단계, 꿔바로우 추가");
            assertThat(item.getUnitPrice()).isEqualTo(12_000);
            assertThat(item.getQuantity()).isEqualTo(2);
            assertThat(item.getAmount()).isEqualTo(24_000);
            assertThat(item.isEditedByHost()).isFalse();
        }

        @Test
        @DisplayName("옵션이 없는 메뉴도 담을 수 있다")
        void allowsNullOptions() {
            OrderItem item = participation.addItem(MEMBER_ID, "공기밥", null, 1_000, 1, OPEN);

            assertThat(item.getOptions()).isNull();
            assertThat(item.getAmount()).isEqualTo(1_000);
        }

        @Test
        @DisplayName("공백만 적은 옵션은 없는 것으로 저장한다")
        void blankOptionsBecomeNull() {
            OrderItem item = participation.addItem(MEMBER_ID, "공기밥", "   ", 1_000, 1, OPEN);

            assertThat(item.getOptions()).isNull();
        }

        @Test
        @DisplayName("같은 메뉴를 옵션만 다르게 여러 줄 담을 수 있다")
        void allowsSameMenuWithDifferentOptions() {
            OrderItem mild = participation.addItem(MEMBER_ID, "마라탕", "1단계", 9_000, 1, OPEN);
            OrderItem hot = participation.addItem(MEMBER_ID, "마라탕", "3단계", 9_000, 1, OPEN);

            assertThat(mild.getOptions()).isEqualTo("1단계");
            assertThat(hot.getOptions()).isEqualTo("3단계");
        }

        @Test
        @DisplayName("남의 참여에는 메뉴를 담을 수 없다")
        void rejectsOtherUser() {
            assertThatThrownBy(() -> participation.addItem(OTHER_ID, "마라탕", null, 9_000, 1, OPEN))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("방장이어도 남의 참여에는 메뉴를 담을 수 없다")
        void rejectsHostOfOtherParticipation() {
            assertThatThrownBy(() -> participation.addItem(HOST_ID, "마라탕", null, 9_000, 1, OPEN))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("마감 시각 정각에는 담을 수 없다")
        void rejectsAtDeadline() {
            // 참여, 나가기와 같은 경계다. 정각은 이미 마감이다
            assertThatThrownBy(() -> participation.addItem(MEMBER_ID, "마라탕", null, 9_000, 1, DEADLINE))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("마감 시각이 지났으면 상태가 아직 모집중이어도 담을 수 없다")
        void rejectsAfterDeadlineWhileRecruiting() {
            assertThat(groupOrder.getStatus()).isEqualTo(GroupOrderStatus.RECRUITING);

            assertThatThrownBy(() -> participation.addItem(MEMBER_ID, "마라탕", null, 9_000, 1, LATE))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("마감된 방에는 메뉴를 담을 수 없다")
        void rejectsWhenClosed() {
            groupOrder.closeByHost(HOST_ID, 2, 20_000);

            assertThatThrownBy(() -> participation.addItem(MEMBER_ID, "마라탕", null, 9_000, 1, OPEN))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("마감 후 방장이라도 새 메뉴를 담을 수는 없다")
        void hostCannotAddAfterClose() {
            // 방장에게 열린 건 고치기뿐이다. 담기까지 열면 주문 뒤에 정산표만 늘어난다
            groupOrder.closeByHost(HOST_ID, 2, 20_000);

            assertThatThrownBy(() -> hostParticipation.addItem(HOST_ID, "공기밥", null, 1_000, 1, OPEN))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("취소된 방에는 메뉴를 담을 수 없다")
        void rejectsWhenCanceled() {
            groupOrder.cancelByHost(HOST_ID, null);

            assertThatThrownBy(() -> participation.addItem(MEMBER_ID, "마라탕", null, 9_000, 1, OPEN))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("메뉴 이름이 없으면 담을 수 없다")
        void rejectsMissingMenuName() {
            assertThatThrownBy(() -> participation.addItem(MEMBER_ID, null, null, 9_000, 1, OPEN))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> participation.addItem(MEMBER_ID, "   ", null, 9_000, 1, OPEN))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("가격이 0원 이하면 담을 수 없다")
        void rejectsNonPositivePrice() {
            assertThatThrownBy(() -> participation.addItem(MEMBER_ID, "마라탕", null, 0, 1, OPEN))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> participation.addItem(MEMBER_ID, "마라탕", null, -1, 1, OPEN))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("개수가 1보다 작으면 담을 수 없다")
        void rejectsNonPositiveQuantity() {
            assertThatThrownBy(() -> participation.addItem(MEMBER_ID, "마라탕", null, 9_000, 0, OPEN))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> participation.addItem(MEMBER_ID, "마라탕", null, 9_000, -1, OPEN))
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
    @DisplayName("메뉴 고치기")
    class Update {

        OrderItem item;
        OrderItem hostItem;

        @BeforeEach
        void addOne() {
            item = participation.addItem(MEMBER_ID, "공기밥", null, 900, 1, OPEN);
            hostItem = hostParticipation.addItem(HOST_ID, "꿔바로우", "소", 12_000, 1, OPEN);
        }

        // 거절됐을 때 원래 값이 그대로인지 본다
        void assertUnchanged() {
            assertThat(item.getMenuName()).isEqualTo("공기밥");
            assertThat(item.getUnitPrice()).isEqualTo(900);
            assertThat(item.getQuantity()).isEqualTo(1);
            assertThat(item.isEditedByHost()).isFalse();
        }

        @Test
        @DisplayName("모집중에 주인이 고치면 값이 통째로 바뀌고 수정 표시는 없다")
        void ownerUpdatesWhileRecruiting() {
            item.update(MEMBER_ID, "공기밥", "많이", 1_000, 2, OPEN);

            assertThat(item.getMenuName()).isEqualTo("공기밥");
            assertThat(item.getOptions()).isEqualTo("많이");
            assertThat(item.getUnitPrice()).isEqualTo(1_000);
            assertThat(item.getQuantity()).isEqualTo(2);
            assertThat(item.getAmount()).isEqualTo(2_000);
            assertThat(item.isEditedByHost()).isFalse();
        }

        @Test
        @DisplayName("고칠 때도 공백만 적은 옵션은 없는 것으로 저장한다")
        void blankOptionsBecomeNull() {
            OrderItem withOptions = participation.addItem(MEMBER_ID, "마라탕", "2단계", 9_000, 1, OPEN);

            withOptions.update(MEMBER_ID, "마라탕", "  ", 9_000, 1, OPEN);

            assertThat(withOptions.getOptions()).isNull();
        }

        @Test
        @DisplayName("모집중에는 방장도 남의 메뉴를 고칠 수 없다")
        void hostCannotUpdateWhileRecruiting() {
            // 모집중에는 주인이 직접 고칠 수 있다. 방장이 끼어들 이유가 없다
            assertThatThrownBy(() -> item.update(HOST_ID, "공기밥", null, 1_000, 1, OPEN))
                    .isInstanceOf(IllegalStateException.class);
            assertUnchanged();
        }

        @Test
        @DisplayName("다른 참여자는 내 메뉴를 고칠 수 없다")
        void otherCannotUpdate() {
            assertThatThrownBy(() -> item.update(OTHER_ID, "공기밥", null, 1_000, 1, OPEN))
                    .isInstanceOf(IllegalStateException.class);
            assertUnchanged();
        }

        @Test
        @DisplayName("마감 시각 정각부터는 주인도 고칠 수 없다")
        void ownerCannotUpdateAtDeadline() {
            assertThatThrownBy(() -> item.update(MEMBER_ID, "공기밥", null, 1_000, 1, DEADLINE))
                    .isInstanceOf(IllegalStateException.class);
            assertUnchanged();
        }

        @Test
        @DisplayName("마감 시각이 지나 아직 모집중이어도 방장은 고칠 수 없다. 먼저 마감해야 한다")
        void hostCannotUpdateAfterDeadlineWhileRecruiting() {
            // 방장에게 열리는 건 상태가 CLOSED일 때다. 시각만 지난 모집중은 아직 아니다
            assertThatThrownBy(() -> item.update(HOST_ID, "공기밥", null, 1_000, 1, LATE))
                    .isInstanceOf(IllegalStateException.class);
            assertUnchanged();
        }

        @Test
        @DisplayName("마감 후 방장이 남의 메뉴를 고치면 바뀌고 수정 표시가 남는다")
        void hostUpdatesAfterClose() {
            groupOrder.closeByHost(HOST_ID, 3, 20_000);

            // 마감 시각이 지난 뒤에도 된다. 검수는 주문 직전에 한다
            item.update(HOST_ID, "공기밥", null, 1_000, 1, LATE);

            assertThat(item.getUnitPrice()).isEqualTo(1_000);
            assertThat(item.isEditedByHost()).isTrue();
        }

        @Test
        @DisplayName("마감 시각 전에 방장이 손으로 마감했으면 그때부터 주인은 못 고치고 방장은 고친다")
        void manualCloseBeforeDeadline() {
            // 최소주문금액이 일찍 차서 19:10에 마감한 방. 마감 시각(19:30)은 아직 안 왔다
            groupOrder.closeByHost(HOST_ID, 3, 20_000);

            assertThatThrownBy(() -> item.update(MEMBER_ID, "공기밥", null, 1_000, 1, OPEN))
                    .isInstanceOf(IllegalStateException.class);
            assertUnchanged();

            item.update(HOST_ID, "공기밥", null, 1_000, 1, OPEN);
            assertThat(item.getUnitPrice()).isEqualTo(1_000);
            assertThat(item.isEditedByHost()).isTrue();
        }

        @Test
        @DisplayName("마감 후 방장이 자기 메뉴를 고치면 수정 표시는 없다")
        void hostUpdatesOwnItemAfterClose() {
            // 표시는 "남이 내 메뉴를 고쳤다"를 알려주려는 것이다. 자기 메뉴면 알릴 사람이 없다
            groupOrder.closeByHost(HOST_ID, 3, 20_000);

            hostItem.update(HOST_ID, "꿔바로우", "중", 15_000, 1, LATE);

            assertThat(hostItem.getUnitPrice()).isEqualTo(15_000);
            assertThat(hostItem.isEditedByHost()).isFalse();
        }

        @Test
        @DisplayName("마감 후에는 주인도 자기 메뉴를 고칠 수 없다")
        void ownerCannotUpdateAfterClose() {
            groupOrder.closeByHost(HOST_ID, 3, 20_000);

            assertThatThrownBy(() -> item.update(MEMBER_ID, "공기밥", null, 1_000, 1, LATE))
                    .isInstanceOf(IllegalStateException.class);
            assertUnchanged();
        }

        @Test
        @DisplayName("마감 후에도 다른 참여자는 고칠 수 없다")
        void otherCannotUpdateAfterClose() {
            groupOrder.closeByHost(HOST_ID, 3, 20_000);

            assertThatThrownBy(() -> item.update(OTHER_ID, "공기밥", null, 1_000, 1, LATE))
                    .isInstanceOf(IllegalStateException.class);
            assertUnchanged();
        }

        @Test
        @DisplayName("정산중에는 방장도 고칠 수 없다")
        void hostCannotUpdateWhileSettling() {
            // 정산표가 이미 그 금액으로 만들어졌다.
            // 정산 시작 메서드가 아직 없어서 상태만 직접 넣는다
            ReflectionTestUtils.setField(groupOrder, "status", GroupOrderStatus.SETTLING);

            assertThatThrownBy(() -> item.update(HOST_ID, "공기밥", null, 1_000, 1, LATE))
                    .isInstanceOf(IllegalStateException.class);
            assertUnchanged();
        }

        @Test
        @DisplayName("취소된 방에서는 아무도 고칠 수 없다")
        void nobodyUpdatesWhenCanceled() {
            groupOrder.cancelByHost(HOST_ID, null);

            assertThatThrownBy(() -> item.update(MEMBER_ID, "공기밥", null, 1_000, 1, OPEN))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> item.update(HOST_ID, "공기밥", null, 1_000, 1, OPEN))
                    .isInstanceOf(IllegalStateException.class);
            assertUnchanged();
        }

        @Test
        @DisplayName("고칠 값이 잘못됐으면 담기와 같은 이유로 거절하고 원래 값은 그대로다")
        void rejectsInvalidValues() {
            assertThatThrownBy(() -> item.update(MEMBER_ID, "  ", null, 1_000, 1, OPEN))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> item.update(MEMBER_ID, "공기밥", null, 0, 1, OPEN))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> item.update(MEMBER_ID, "공기밥", null, 1_000, 0, OPEN))
                    .isInstanceOf(IllegalArgumentException.class);
            assertUnchanged();
        }
    }

    @Nested
    @DisplayName("메뉴 빼기")
    class RemoveItem {

        OrderItem item;

        @BeforeEach
        void addOne() {
            item = participation.addItem(MEMBER_ID, "마라탕", "2단계", 9_000, 1, OPEN);
        }

        @Test
        @DisplayName("모집중에 본인 메뉴는 뺄 수 있다")
        void removes() {
            assertThatCode(() -> participation.removeItem(MEMBER_ID, item, OPEN))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("남이 내 메뉴를 뺄 수 없다")
        void rejectsOtherUser() {
            assertThatThrownBy(() -> participation.removeItem(OTHER_ID, item, OPEN))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("마감 시각 정각에는 뺄 수 없다")
        void rejectsAtDeadline() {
            assertThatThrownBy(() -> participation.removeItem(MEMBER_ID, item, DEADLINE))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("마감된 방에서는 뺄 수 없다")
        void rejectsWhenClosed() {
            groupOrder.closeByHost(HOST_ID, 2, 20_000);

            assertThatThrownBy(() -> participation.removeItem(MEMBER_ID, item, OPEN))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("마감 후 방장도 남의 메뉴를 뺄 수는 없다")
        void hostCannotRemoveAfterClose() {
            // 방장에게 열린 건 고치기뿐이다 (ADR-038)
            groupOrder.closeByHost(HOST_ID, 2, 20_000);

            assertThatThrownBy(() -> participation.removeItem(HOST_ID, item, LATE))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("다른 참여의 메뉴는 뺄 수 없다")
        void rejectsItemOfOtherParticipation() {
            OrderItem otherItem = otherParticipation.addItem(OTHER_ID, "꿔바로우", null, 12_000, 1, OPEN);

            assertThatThrownBy(() -> participation.removeItem(MEMBER_ID, otherItem, OPEN))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
