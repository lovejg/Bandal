package com.bandal.grouporder.dto;

import com.bandal.settlement.RefundStatus;

import java.time.Instant;

// 내 방 목록 한 줄. 목록만 보고 "여기 들어가서 뭘 해야 하나"를 알 수 있게 상태값을 담는다
// 다음에 할 일을 문구로 만들지 않는다. 문구는 화면이 이 값들을 보고 고른다
public record MyGroupOrderResponse(
        Long id,
        String storeName,
        String status,
        // 내가 이 방의 방장인지. 아래 칸 중 어느 쪽을 볼지 화면이 이걸로 고른다
        boolean host,
        // 아래 셋은 참여자의 내 줄이다. 방장이거나 아직 정산표가 없으면 null
        Instant markedPaidAt,
        Instant confirmedPaidAt,
        RefundStatus refundStatus,
        // 방장만. 정산중인 방에서 보냈어요는 눌렀는데 아직 확인하지 않은 참여자 수. 참여자면 null
        Long awaitingConfirmCount
) {
}
