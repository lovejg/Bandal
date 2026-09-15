package com.bandal.grouporder;

// 공구방 상태. 흐름은 DESIGN.md 5번 참고.
// 모집중 -> 마감 -> 주문완료 -> 배달완료 -> 정산완료, 모집중과 마감에서는 취소로 갈 수 있다.
public enum GroupOrderStatus {
    RECRUITING, // 모집중
    CLOSED,     // 마감
    ORDERED,    // 주문완료
    DELIVERED,  // 배달완료
    SETTLED,    // 정산완료
    CANCELED    // 취소
}
