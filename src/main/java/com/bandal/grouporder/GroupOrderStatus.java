package com.bandal.grouporder;

// 공구방 상태. 흐름은 DESIGN.md 5번 참고.
// 모집중 -> 마감 -> 정산중 -> 주문완료 -> 배달완료, 주문완료 전에는 취소로 갈 수 있다.
// 송금이 주문보다 앞에 있어서 정산중이 주문완료 앞이다 (ADR-029)
public enum GroupOrderStatus {
    RECRUITING, // 모집중
    CLOSED,     // 마감
    SETTLING,   // 정산중. 배달비가 입력되고 정산표가 만들어졌다. 참여자들이 송금하는 구간
    ORDERED,    // 주문완료. 전원 입금이 확인되어 방장이 실제로 결제했다
    DELIVERED,  // 배달완료
    CANCELED    // 취소
}
