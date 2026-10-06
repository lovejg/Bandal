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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// DB 없이 참여 행의 규칙만 확인한다. 메뉴 담기·빼기는 OrderItemTest에 있다
class ParticipationTest {

    static final Long HOST_ID = 1_000L;
    static final Long MEMBER_ID = 1_001L;
    static final Long OTHER_ID = 1_002L;
    static final Instant DEADLINE = Instant.parse("2026-10-01T10:30:00Z");
    static final Instant JOINED_AT = Instant.parse("2026-10-01T09:00:00Z");
    static final Instant BEFORE_DEADLINE = DEADLINE.minusSeconds(60);

    GroupOrder groupOrder;
    Participation hostParticipation;
    Participation memberParticipation;

    @BeforeEach
    void setUp() {
        University university = new University("한국대학교", "hankuk.ac.kr");
        ReflectionTestUtils.setField(university, "id", 10L);
        PickupSpot pickupSpot = new PickupSpot(university, "제1기숙사 로비", null);
        User host = new User(university, "kim@hankuk.ac.kr", "hashed-password", "배고파");
        host.registerAccount("한국은행", "110-123-456789", "김민수");
        User member = new User(university, "lee@hankuk.ac.kr", "hashed-password", "마라탕러버");

        // 저장하지 않은 엔티티라 id가 null이다. 주인 확인을 테스트하려고 id만 직접 넣는다.
        // 값만 같은 새 Long을 넣어서 요청 id와 다른 객체가 되게 한다
        ReflectionTestUtils.setField(host, "id", Long.valueOf(HOST_ID.longValue()));
        ReflectionTestUtils.setField(member, "id", Long.valueOf(MEMBER_ID.longValue()));

        groupOrder = new GroupOrder(host, pickupSpot, "○○마라탕", 15_000, DEADLINE, 4);
        hostParticipation = new Participation(groupOrder, host, JOINED_AT);
        memberParticipation = new Participation(groupOrder, member, JOINED_AT);
    }

    @Nested
    @DisplayName("나가기 검사")
    class CheckLeavable {

        @Test
        @DisplayName("참여자는 모집중이고 마감 전이면 나갈 수 있다")
        void allowsMember() {
            assertThatCode(() -> memberParticipation.checkLeavable(MEMBER_ID, BEFORE_DEADLINE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("남의 참여로는 나갈 수 없다")
        void rejectsOtherUser() {
            // 참여 id만 알면 남을 방에서 내보낼 수 있으면 안 된다
            assertThatThrownBy(() -> memberParticipation.checkLeavable(OTHER_ID, BEFORE_DEADLINE))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("방장은 나갈 수 없다")
        void rejectsHost() {
            // 방장이 빠지는 길은 방 취소다 (ADR-037)
            assertThatThrownBy(() -> hostParticipation.checkLeavable(HOST_ID, BEFORE_DEADLINE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("방장은 나갈 수 없습니다. 방을 취소해주세요");
        }

        @Test
        @DisplayName("마감 시각 정각에는 이미 늦었다")
        void rejectsAtDeadline() {
            assertThatThrownBy(() -> memberParticipation.checkLeavable(MEMBER_ID, DEADLINE))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("마감된 방에서는 나갈 수 없다")
        void rejectsClosedRoom() {
            groupOrder.closeByHost(HOST_ID, 2, 20_000, BEFORE_DEADLINE);

            assertThatThrownBy(() -> memberParticipation.checkLeavable(MEMBER_ID, BEFORE_DEADLINE))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("취소된 방에서는 나갈 수 없다")
        void rejectsCanceledRoom() {
            // 취소된 방의 명단은 취소 기록의 일부다. 나중에 지우지 않는다
            groupOrder.cancelByHost(HOST_ID, null);

            assertThatThrownBy(() -> memberParticipation.checkLeavable(MEMBER_ID, BEFORE_DEADLINE))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("메뉴 보기 검사")
    class CheckItemsViewable {

        @Test
        @DisplayName("주인은 자기 메뉴를 볼 수 있다")
        void allowsOwner() {
            assertThatCode(() -> memberParticipation.checkItemsViewable(MEMBER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("방장은 남의 메뉴를 볼 수 있다")
        void allowsHost() {
            // 검수해야 한다 (ADR-039)
            assertThatCode(() -> memberParticipation.checkItemsViewable(HOST_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("주인도 방장도 아니면 볼 수 없다")
        void rejectsOtherUser() {
            assertThatThrownBy(() -> memberParticipation.checkItemsViewable(OTHER_ID))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("상태는 보지 않는다. 마감, 취소된 방에서도 주인은 볼 수 있다")
        void ignoresStatus() {
            groupOrder.closeByHost(HOST_ID, 2, 20_000, BEFORE_DEADLINE);
            assertThatCode(() -> memberParticipation.checkItemsViewable(MEMBER_ID))
                    .doesNotThrowAnyException();

            groupOrder.cancelByHost(HOST_ID, "가게가 문을 닫았습니다");
            assertThatCode(() -> memberParticipation.checkItemsViewable(MEMBER_ID))
                    .doesNotThrowAnyException();
        }
    }
}
