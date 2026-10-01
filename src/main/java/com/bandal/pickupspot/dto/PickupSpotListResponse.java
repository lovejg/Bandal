package com.bandal.pickupspot.dto;

import java.util.List;

// 내 학교 거점 전부. 배열을 그대로 주지 않고 한 번 감싼다.
// 나중에 검색어나 개수 같은 값을 옆에 붙여도 기존 앱이 깨지지 않는다
public record PickupSpotListResponse(
        List<PickupSpotResponse> spots
) {
}
