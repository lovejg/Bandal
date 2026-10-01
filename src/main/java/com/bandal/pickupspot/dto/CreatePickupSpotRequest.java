package com.bandal.pickupspot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 학교는 본문이 아니라 토큰의 사용자에서 읽는다. 본문으로 받으면 남의 학교에 거점을 만들 수 있다
public record CreatePickupSpotRequest(

        @NotBlank(message = "거점 이름을 적어주세요")
        @Size(max = 30, message = "거점 이름은 30자 이하여야 합니다")
        String name,

        // 선택. 없으면 null
        @Size(max = 100, message = "설명은 100자 이하여야 합니다")
        String description
) {
}
