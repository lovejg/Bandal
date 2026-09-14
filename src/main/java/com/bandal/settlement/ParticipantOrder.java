package com.bandal.settlement;

// 정산 계산기에 넣는 참여자 한 명의 주문 정보.
// DB나 엔티티와는 아직 아무 관계가 없는 순수한 값이다.
public record ParticipantOrder(
        Long userId,

        boolean host,

        long menuTotalPrice
) {
}
