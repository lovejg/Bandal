package com.bandal.participation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ParticipationRepository extends JpaRepository<Participation, Long> {

    Optional<Participation> findById(Long id);

    // 방의 현재 인원(방장 포함)
    long countByGroupOrderId(Long groupOrderId);

    // 이미 참여했는지
    boolean existsByGroupOrderIdAndUserId(Long groupOrderId, Long userId);
}
