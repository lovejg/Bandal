package com.bandal.grouporder;

// 방에 생긴 변경의 종류. 상태 변경 기록, outbox 줄, Kafka 글, 알림 줄이 모두 이 값을 함께 들고 다닌다
// 토픽 이름(group-order-status)은 그대로 두고 "방에 생긴 변경"으로 뜻을 넓혔다. 같은 방의 변경이 한 칸에 줄을 서야 순서가 지켜지기 때문이다
public enum GroupOrderChangeKind {
    // 상태가 바뀌었다(마감, 정산 시작, 주문, 배달완료, 취소). from과 to가 다르다
    STATUS_CHANGED,
    // 정산중에 방장이 배달비를 고쳤다. 상태는 그대로라 from과 to가 둘 다 SETTLING이다. 사람마다 낼 돈이 바뀐다
    DELIVERY_FEE_CHANGED
}
