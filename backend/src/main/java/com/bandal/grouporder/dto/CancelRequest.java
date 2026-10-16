package com.bandal.grouporder.dto;

import jakarta.validation.constraints.Size;

// 방장의 취소. 사유는 모집중이면 비워도 되고 마감, 정산중이면 꼭 적어야 한다
// 필요한지는 방 상태에 따라 달라서 여기서는 길이만 보고 나머지는 엔티티가 본다
public record CancelRequest(

        @Size(max = 200, message = "취소 사유는 200자까지 적을 수 있습니다")
        String reason
) {
}
