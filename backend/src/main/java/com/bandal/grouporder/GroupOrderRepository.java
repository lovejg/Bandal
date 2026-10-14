package com.bandal.grouporder;

import com.bandal.common.NotFoundException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface GroupOrderRepository extends JpaRepository<GroupOrder, Long> {

    // 없으면 404. 서비스마다 같은 orElseThrow를 반복하지 않으려고 둔다
    // default 메서드라 스프링이 이름으로 쿼리를 만들지 않고 이 몸통을 그대로 쓴다
    default GroupOrder findByIdOrThrow(Long id) {
        return findById(id).orElseThrow(() -> new NotFoundException("없는 방입니다"));
    }

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

    // 스케줄러가 넘길 후보의 id. 모집중은 마감 시각으로, 나머지는 그 상태가 된 시각으로 고른다 (ADR-051)
    // 기준 시각(~Before)은 서비스 쪽에서 now에서 기간을 빼서 넘긴다. 최종 판단은 엔티티가 다시 한다 (ADR-052)
    // 엔티티 대신 id만 받는 건 방마다 따로 읽어서 따로 처리하기 위해서다
    @Query("""
        SELECT go.id FROM GroupOrder go
        WHERE (go.status = com.bandal.grouporder.GroupOrderStatus.RECRUITING AND go.deadlineAt <= :now)
           OR (go.status = com.bandal.grouporder.GroupOrderStatus.CLOSED AND go.closedAt <= :closedBefore)
           OR (go.status = com.bandal.grouporder.GroupOrderStatus.SETTLING AND go.settlementStartedAt <= :settlingBefore)
           OR (go.status = com.bandal.grouporder.GroupOrderStatus.ORDERED AND go.orderedAt <= :orderedBefore)
        ORDER BY go.id
        """)
    List<Long> findIdsToExpire(@Param("now") Instant now,
                               @Param("closedBefore") Instant closedBefore,
                               @Param("settlingBefore") Instant settlingBefore,
                               @Param("orderedBefore") Instant orderedBefore);

    // 내 방 목록의 진행 중 탭
    // 정렬: 방에 생성 시각 칸이 없어서 id 내림차순을 "최근에 만든 순"으로 쓴다
    @Query("""
        SELECT go FROM Participation p
        JOIN p.groupOrder go
        WHERE p.user.id = :userId
        AND (go.status IN (com.bandal.grouporder.GroupOrderStatus.RECRUITING,
                           com.bandal.grouporder.GroupOrderStatus.CLOSED,
                           com.bandal.grouporder.GroupOrderStatus.SETTLING,
                           com.bandal.grouporder.GroupOrderStatus.ORDERED)
             OR EXISTS (SELECT 1 FROM Settlement s
                        WHERE s.groupOrder = go
                        AND s.host = false
                        AND go.cancelType IN (com.bandal.grouporder.CancelType.HOST_WHILE_SETTLING,
                                              com.bandal.grouporder.CancelType.SETTLING_TIMEOUT)
                        AND (s.markedPaidAt IS NOT NULL OR s.confirmedPaidAt IS NOT NULL)
                        AND s.refundReceivedAt IS NULL
                        AND (go.host.id = :userId OR s.user.id = :userId)))
        ORDER BY go.id DESC
        """)
    List<GroupOrder> findMyActive(@Param("userId") Long userId);

    // 내 방 목록의 끝난 방 탭. 진행 중 탭의 정확히 나머지다
    @Query("""
        SELECT go FROM Participation p
        JOIN p.groupOrder go
        WHERE p.user.id = :userId
        AND (go.status IN (com.bandal.grouporder.GroupOrderStatus.DELIVERED,
                           com.bandal.grouporder.GroupOrderStatus.CANCELED)
        AND NOT EXISTS (SELECT 1 FROM Settlement s
                        WHERE s.groupOrder = go
                        AND s.host = false
                        AND go.cancelType IN (com.bandal.grouporder.CancelType.HOST_WHILE_SETTLING,
                                              com.bandal.grouporder.CancelType.SETTLING_TIMEOUT)
                        AND (s.markedPaidAt IS NOT NULL OR s.confirmedPaidAt IS NOT NULL)
                        AND s.refundReceivedAt IS NULL
                        AND (go.host.id = :userId OR s.user.id = :userId)))
        ORDER BY COALESCE(go.deliveredAt, go.canceledAt) DESC, go.id DESC
        """)
    Slice<GroupOrder> findMyDone(@Param("userId") Long userId, Pageable pageable);
}
