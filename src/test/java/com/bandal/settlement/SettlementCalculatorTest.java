package com.bandal.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementCalculatorTest {

    private final SettlementCalculator calculator = new SettlementCalculator();

    @Test
    @DisplayName("3명이 배달비 3000원을 1000원씩 나눠 낸다")
    void splitsDeliveryFeeEvenly() {
        // given: 누가 얼마어치 담았는지 준비한다
        List<ParticipantOrder> participants = List.of(
                new ParticipantOrder(1L, true, 8000L),
                new ParticipantOrder(2L, false, 9000L),
                new ParticipantOrder(3L, false, 7000L)
        );

        // when: 계산기를 실행한다
        List<SettlementResult> results = calculator.calculate(participants, 3000L);

        // then: 결과가 기대한 값인지 확인한다
        // record는 필드 값이 모두 같으면 같은 객체로 취급해서 이렇게 통째로 비교할 수 있다.
        // InAnyOrder라서 결과 리스트의 순서는 상관없다.
        assertThat(results).containsExactlyInAnyOrder(
                new SettlementResult(1L, 8000L, 1000L, 9000L),
                new SettlementResult(2L, 9000L, 1000L, 10000L),
                new SettlementResult(3L, 7000L, 1000L, 8000L)
        );
    }

    @Test
    @DisplayName("7명이 배달비 3000원을 나누면 나누어떨어지지 않고 남는 4원은 방장이 낸다")
    void hostPaysRemainder() {
        // given: 7명 모두 10000원어치를 담았다. 방장은 1번.
        // 3000 / 7 = 428.57... 이라 멤버는 428원, 방장은 3000 - 428 x 6 = 432원이다.
        List<ParticipantOrder> participants = List.of(
                new ParticipantOrder(1L, true, 10000L),
                new ParticipantOrder(2L, false, 10000L),
                new ParticipantOrder(3L, false, 10000L),
                new ParticipantOrder(4L, false, 10000L),
                new ParticipantOrder(5L, false, 10000L),
                new ParticipantOrder(6L, false, 10000L),
                new ParticipantOrder(7L, false, 10000L)
        );

        // when
        List<SettlementResult> results = calculator.calculate(participants, 3000L);

        // then 1: 모두의 부담액을 더하면 실제 주문 금액(메뉴 70000 + 배달비 3000)과 정확히 같다.
        // 이 조건이 이번 단계의 핵심이라 먼저 검사한다.
        long sumOfTotalPrice = results.stream()
                .mapToLong(SettlementResult::totalPrice)
                .sum();
        assertThat(sumOfTotalPrice).isEqualTo(73000L);

        // then 2: 남는 4원은 방장에게만 붙는다.
        assertThat(results).containsExactlyInAnyOrder(
                new SettlementResult(1L, 10000L, 432L, 10432L),
                new SettlementResult(2L, 10000L, 428L, 10428L),
                new SettlementResult(3L, 10000L, 428L, 10428L),
                new SettlementResult(4L, 10000L, 428L, 10428L),
                new SettlementResult(5L, 10000L, 428L, 10428L),
                new SettlementResult(6L, 10000L, 428L, 10428L),
                new SettlementResult(7L, 10000L, 428L, 10428L)
        );
    }

    // 3단계: 방장은 정확히 한 명이어야 한다.
    // 방장 몫 = 배달비 - 멤버 몫 x (인원 - 1) 식은 방장이 한 명이라는 전제 위에서만 맞는다.

    @Test
    @DisplayName("참여자가 아무도 없으면 예외가 난다")
    void throwsWhenNoParticipants() {
        assertThatThrownBy(() -> calculator.calculate(List.of(), 3000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("방장이 없으면 예외가 난다")
    void throwsWhenNoHost() {
        List<ParticipantOrder> participants = List.of(
                new ParticipantOrder(1L, false, 8000L),
                new ParticipantOrder(2L, false, 9000L)
        );

        assertThatThrownBy(() -> calculator.calculate(participants, 3000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("방장이 두 명이면 예외가 난다")
    void throwsWhenMoreThanOneHost() {
        List<ParticipantOrder> participants = List.of(
                new ParticipantOrder(1L, true, 8000L),
                new ParticipantOrder(2L, true, 9000L),
                new ParticipantOrder(3L, false, 7000L)
        );

        assertThatThrownBy(() -> calculator.calculate(participants, 3001L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
