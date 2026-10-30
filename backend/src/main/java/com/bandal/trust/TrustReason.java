package com.bandal.trust;

// 점수가 움직인 이유. 이름 그대로 DB에 저장하고, 화면이 문구로 바꾼다
public enum TrustReason {
    // 배달완료까지 함께했다. 방장, 참여자 모두 +2
    DELIVERED(2),
    // 방장이 마감 뒤에 취소했거나, 마감 뒤 배달비를 넣지 않아 자동 취소됐다. 참여자들이 기다리다 허탕을 쳤다
    HOST_CANCELED_AFTER_CLOSE(-5),
    // 방장이 정산중에 취소했다. 이미 돈이 방장에게 갔다. 피해가 가장 크다
    HOST_CANCELED_WHILE_SETTLING(-15),
    // 정산중에 전원이 보냈는데 방장이 주문하지 않아 시간이 지나 자동 취소됐다
    HOST_DID_NOT_ORDER(-15),
    // 정산중에 시간이 지나 자동 취소될 때까지 "보냈어요"를 누르지 않았다. 전원 입금이 확인돼야 주문할 수 있어서
    // 이 사람 때문에 방장이 주문을 못 했을 수 있다. 이때 방장은 깎지 않는다
    DID_NOT_PAY(-5);

    private final int delta;

    TrustReason(int delta) {
        this.delta = delta;
    }

    public int delta() {
        return delta;
    }
}
