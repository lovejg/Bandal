package com.bandal.university;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UniversityRepository extends JpaRepository<University, Long> {

    // 한 도메인에 캠퍼스가 여럿일 수 있어서 List로 받는다. 없으면 빈 리스트다
    List<University> findAllByEmailDomainOrderById(String emailDomain);
}
