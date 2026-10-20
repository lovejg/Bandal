package com.bandal.university;

import com.bandal.university.dto.UniversityListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/universities")
@RequiredArgsConstructor
public class UniversityController {

    private final UniversityService universityService;

    // 가입 전에 부르므로 로그인 없이 연다(SecurityConfig). 학교 이름은 숨길 정보가 아니다
    @GetMapping
    public UniversityListResponse findByDomain(@RequestParam String domain) {
        return universityService.findByDomain(domain);
    }
}
