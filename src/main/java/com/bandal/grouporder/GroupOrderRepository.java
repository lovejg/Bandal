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
