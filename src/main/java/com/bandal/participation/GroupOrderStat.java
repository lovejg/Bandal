package com.bandal.participation;

// 방 id와 그 방의 숫자 하나(인원수, 메뉴 합계 등)를 묶은 한 쌍.
// 여러 방을 한 번에 세거나 더하는 쿼리가 방마다 한 줄씩 이걸로 돌려준다
public record GroupOrderStat(Long groupOrderId, long value) {
}
