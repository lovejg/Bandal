package com.bandal.university.dto;

import com.bandal.university.University;

// 가입 화면에서 고를 캠퍼스 하나. 도메인은 화면이 이미 알고 있어서 돌려주지 않는다
public record UniversityResponse(
        Long id,
        String name
) {
    public static UniversityResponse of(University university) {
        return new UniversityResponse(university.getId(), university.getName());
    }
}
