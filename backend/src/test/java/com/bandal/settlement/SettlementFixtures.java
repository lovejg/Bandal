package com.bandal.settlement;

import com.bandal.grouporder.GroupOrder;
import com.bandal.user.User;

import java.time.Instant;

// 다른 패키지의 테스트가 정산 줄을 바로 만들 때 쓴다. Settlement의 생성자는 정산 계산만 부르도록 패키지 안에 묶여 있다
public final class SettlementFixtures {

    private SettlementFixtures() {
    }

    // 금액은 0원. 담은 메뉴 줄이 없는데 금액을 넣으면, 남은 데이터 전체의 "정산 줄 = 메뉴 합" 규칙을 보는 DemoSeedTest가 깨진다
    // (12,000원으로 넣었다가 실제로 깨졌다). 환불, 탈퇴 판단에는 금액이 쓰이지 않는다
    public static Settlement line(GroupOrder groupOrder, User user, boolean host) {
        return new Settlement(groupOrder, user, 0, 0, host, Instant.now());
    }
}
