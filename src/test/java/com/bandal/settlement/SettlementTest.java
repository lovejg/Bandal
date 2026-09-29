package com.bandal.settlement;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// DB 없이 정산표 한 줄의 규칙만 확인한다 (ADR-030, 032)
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
        member = new User(university, "lee@hankuk.ac.kr", "hashed-password", "마라탕러버");
        // 저장하지 않은 엔티티라 id가 null이다. 값은 같지만 다른 Long 객체를 넣어서
        // 실제 상황(DB에서 읽은 id와 요청에서 온 id)과 같게 만든다
        ReflectionTestUtils.setField(host, "id", Long.valueOf(HOST_ID.longValue()));
        ReflectionTestUtils.setField(member, "id", Long.valueOf(MEMBER_ID.longValue()));

        groupOrder = new GroupOrder(host, pickupSpot, "○○마라탕", 15_000,
                Instant.parse("2026-09-26T10:30:00Z"), 4);
    }

    // 참여자 줄. 메뉴 9,000원에 배달비 분담 1,166원
    Settlement memberLine() {
        return new Settlement(groupOrder, member, 9_000, 1_166, false, NOW);
    }

    // 방장 줄. 나누어떨어지지 않는 나머지를 방장이 떠안는다 (ADR-001)
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
            // 방장 예외 분기가 생긴다 (ADR-030)
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
            assertThat(line.isDisputed()).isTrue();
        }

        @Test
        @DisplayName("남의 줄에는 누를 수 없다")
        void rejectsOther() {
            Settlement line = memberLine();

            assertThatThrownBy(() -> line.markPaid(OTHER_ID, NOW))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(line.getMarkedPaidAt()).isNull();
        }

        @Test
        @DisplayName("방장이 남의 줄에 대신 누를 수도 없다")
        void rejectsHost() {
            Settlement line = memberLine();

            assertThatThrownBy(() -> line.markPaid(HOST_ID, NOW))
                    .isInstanceOf(IllegalStateException.class);
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
            assertThat(line.isDisputed()).isFalse();
        }

        @Test
        @DisplayName("참여자가 스스로 확인할 수는 없다")
        void rejectsMember() {
            // 이걸 허용하면 이체도 안 하고 방장 돈으로 주문을 시킬 수 있다 (ADR-032)
            Settlement line = memberLine();

            assertThatThrownBy(() -> line.confirm(MEMBER_ID, NOW))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(line.isConfirmed()).isFalse();
        }

        @Test
        @DisplayName("참여자가 보냈다고 안 눌렀어도 방장은 확인할 수 있다")
        void confirmsWithoutMark() {
            // 현금으로 받았거나 참여자가 누르는 걸 잊었을 수 있다
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
}
