package com.bandal.settlement;

import java.util.List;

// 참여자들의 메뉴 합계(비용)와 배달비를 받아서 각자 부담할 금액을 계산한다.
// Spring도 JPA도 쓰지 않는 순수 자바 클래스다. 그래서 DB 없이 테스트가 바로 돈다.
// 배달비의 경우, 1/n을 하는데, 나누어떨어지지 않아서 남는 몇 원은 방장이 떠안는다.
public class SettlementCalculator {

    public List<SettlementResult> calculate(List<ParticipantOrder> participants, long deliveryFee) {
        long hostCount = participants.stream().filter(p -> p.host()).count();
        if (hostCount != 1) {
            throw new IllegalArgumentException("방장은 한 명이어야 한다. 현재 방장 수: " + hostCount);
        }

        long generalDeliveryFee = deliveryFee / participants.size();
        long hostDeliveryFee = deliveryFee - generalDeliveryFee * (participants.size() - 1);

        return participants.stream()
                .map(p -> {
                    long feeShare = p.host() ? hostDeliveryFee : generalDeliveryFee;

                    return new SettlementResult(p.userId(), p.menuTotalPrice(), feeShare,
                            p.menuTotalPrice() + feeShare);
                }).toList();
    }
}
