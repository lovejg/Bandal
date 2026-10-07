package com.bandal.grouporder;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface GroupOrderRepository extends JpaRepository<GroupOrder, Long> {
    Optional<GroupOrder> findById(Long id);

    // 이 사람이 방장인 방 중에 이 상태인 방이 하나라도 있는지. 정산중인 방장의 계좌 변경을 막을 때 쓴다
    // 메서드 이름으로 만드는 쿼리다. host.id와 status로 찾고, exists라 하나만 찾으면 멈춘다
    boolean existsByHostIdAndStatus(Long hostId, GroupOrderStatus status);

    // 방 목록 조회용 쿼리
    @Query("""
        SELECT go FROM GroupOrder go
        JOIN FETCH go.host
        JOIN FETCH go.pickupSpot ps
        WHERE go.status = com.bandal.grouporder.GroupOrderStatus.RECRUITING
        AND ps.university.id = :universityId
        AND go.deadlineAt > :now
        AND (SELECT COUNT(p) FROM Participation p WHERE p.groupOrder = go) < go.capacity
        ORDER BY go.deadlineAt ASC
        """)
    Slice<GroupOrder> findRecruiting(@Param("universityId") Long universityId,
                                     @Param("now") Instant now,
                                     Pageable pageable);
}
