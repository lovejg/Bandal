package com.bandal.university;

import com.bandal.university.dto.UniversityListResponse;
import com.bandal.university.dto.UniversityResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

// 가입 화면이 이메일 도메인으로 고를 캠퍼스를 묻는다
@Service
@RequiredArgsConstructor
public class UniversityService {

    private final UniversityRepository universityRepository;

    // 가입할 때처럼 도메인을 소문자로 맞춘다. 화면에서 YONSEI.ac.kr로 쳐도 같은 목록이 나와야 한다
    @Transactional(readOnly = true)
    public UniversityListResponse findByDomain(String domain) {
        String normalized = domain.trim().toLowerCase(Locale.ROOT);
        return new UniversityListResponse(universityRepository.findAllByEmailDomainOrderById(normalized).stream()
                .map(UniversityResponse::of)
                .toList());
    }
}
