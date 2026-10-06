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

    // 방 상세에서 "보는 사람의 참여 행"을 찾는다. (방, 사용자) 유니크라 많아야 하나다
    // 그냥 쉽게 생각해서 해당 방에서 이 사람의 참여 행 찾아달라 쿼리
    Optional<Participation> findByGroupOrderIdAndUserId(Long groupOrderId, Long userId);

    // 검수 목록의 명단을 사용자까지 한 번에 가져온다(nickname으로 인한 N+1 문제 해결)
    // 방장은 방을 만들 때 참여해서 들어온 순서로 정렬하면 맨 앞에 온다. joinedAt이 같을 때를 대비해 id로 한 번 더 정렬한다
    @Query("""
        SELECT p FROM Participation p
        JOIN FETCH p.user
        WHERE p.groupOrder.id = :groupOrderId
        ORDER BY p.joinedAt, p.id
        """)
    List<Participation> findRosterWithUser(@Param("groupOrderId") Long groupOrderId);

    // 방 목록 조회에서 사용. 한 번에 조건에 맞는 방의 인원 수를 모두 가져옴으로써 N+1 문제를 방지
    @Query("""
        SELECT new com.bandal.participation.GroupOrderStat(p.groupOrder.id, COUNT(p)) FROM Participation p
        WHERE p.groupOrder.id IN :groupOrderIds
        GROUP BY p.groupOrder.id
        """)
    List<GroupOrderStat> countByGroupOrderIds(@Param("groupOrderIds") List<Long> groupOrderIds);
}
