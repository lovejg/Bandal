package com.bandal.pickupspot.dto;

import com.bandal.pickupspot.PickupSpot;

// 거점 한 줄. 목록의 한 줄이자 생성 응답이다.
// 생성 응답에 id가 있어야 앱이 그 id로 바로 방을 만들 수 있다.
// 학교는 넣지 않는다. 목록은 전부 내 학교 거점이라 줄마다 보여줄 필요가 없다
public record PickupSpotResponse(
        Long id,
        String name,
        String description
) {
    public static PickupSpotResponse of(PickupSpot pickupSpot) {
        return new PickupSpotResponse(pickupSpot.getId(), pickupSpot.getName(), pickupSpot.getDescription());
    }
}
