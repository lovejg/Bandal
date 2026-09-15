package com.bandal.university;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UniversityRepository extends JpaRepository<University, Long> {

    // 가입할 때 이메일의 @ 뒷부분으로 어느 학교인지 찾는다
    Optional<University> findByEmailDomain(String emailDomain);
}
