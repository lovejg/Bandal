package com.bandal.university.dto;

import java.util.List;

// 한 메일 도메인을 쓰는 캠퍼스 전부. 거점 목록처럼 배열을 한 번 감싼다
public record UniversityListResponse(
        List<UniversityResponse> universities
) {
}
