package com.bandal.participation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ParticipationRepository extends JpaRepository<Participation, Long> {

    Optional<Participation> findById(Long id);

    // 정산표를 만들 때 방의 명단을 가져온다
    List<Participation> findByGroupOrderId(Long groupOrderId);

    // 방의 현재 인원(방장 포함)
    long countByGroupOrderId(Long groupOrderId);

    // 이미 참여했는지
    boolean existsByGroupOrderIdAndUserId(Long groupOrderId, Long userId);

    // 방 목록 조회에서 사용. 한 번에 조건에 맞는 방의 인원 수를 모두 가져옴으로써 N+1 문제를 방지
    @Query("""
        SELECT new com.bandal.participation.GroupOrderStat(p.groupOrder.id, COUNT(p)) FROM Participation p
        WHERE p.groupOrder.id IN :groupOrderIds
        GROUP BY p.groupOrder.id
        """)
    List<GroupOrderStat> countByGroupOrderIds(@Param("groupOrderIds") List<Long> groupOrderIds);
}
