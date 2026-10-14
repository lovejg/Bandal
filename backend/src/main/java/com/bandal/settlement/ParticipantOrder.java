package com.bandal.settlement;

// 정산 계산기에 넣는 참여자 한 명의 주문 정보.
// DB나 엔티티와 관계없는 순수한 값이다. 계산기를 DB 없이 테스트하려고 따로 둔다
public record ParticipantOrder(
    Long userId,

    boolean host,

    long menuTotalAmount
) {
}
