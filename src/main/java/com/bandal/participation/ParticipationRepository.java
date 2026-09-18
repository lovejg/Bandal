package com.bandal.participation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ParticipationRepository extends JpaRepository<Participation, Long> {

    // 방의 현재 인원(방장 포함). 정원 검사와 최소 2인 검사에 쓴다
    long countByGroupOrderId(Long groupOrderId);
}
