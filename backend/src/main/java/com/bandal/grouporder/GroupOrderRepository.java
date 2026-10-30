package com.bandal.grouporder;

import com.bandal.common.NotFoundException;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GroupOrderRepository extends JpaRepository<GroupOrder, Long> {

    // 없으면 404. 서비스마다 같은 orElseThrow를 반복하지 않으려고 둔다
    // default 메서드라 스프링이 이름으로 쿼리를 만들지 않고 이 몸통을 그대로 쓴다
    default GroupOrder findByIdOrThrow(Long id) {
        return findById(id).orElseThrow(() -> new NotFoundException("없는 방입니다"));
    }

    // 비관적 락. 방의 상태나 인원을 바꾸는 요청은 이걸로 읽어서 같은 방의 요청을 한 줄로 세운다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT go FROM GroupOrder go WHERE go.id = :id")
    Optional<GroupOrder> findByIdForUpdate(@Param("id") Long id);

    // 락을 잡고 읽되, 없으면 404. findByIdOrThrow와 같은 이유로 둔다
    default GroupOrder findByIdForUpdateOrThrow(Long id) {
        return findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("없는 방입니다"));
    }

    // 이 사람이 방장인 방 중에 이 상태인 방이 하나라도 있는지. 정산중인 방장의 계좌 변경을 막을 때 쓴다
    // 메서드 이름으로 만드는 쿼리다. host.id와 status로 찾고, exists라 하나만 찾으면 멈춘다
    boolean existsByHostIdAndStatus(Long hostId, GroupOrderStatus status);

    // 이 사람이 방장이거나 참여 중인 방 중에 아직 끝나지 않은 방(finished에 없는 상태)이 있는지.
    // 닉네임 변경을 막을 때 쓴다. 방장도 참여 줄이 있는지는 기대지 않고 host와 참여 줄을 둘 다 본다
    // 참여 줄은 서브쿼리 EXISTS로 본다. JOIN으로 붙이면 참여자 수만큼 줄이 불어난다(여기선 있는지만 알면 된다)
    @Query("""
        SELECT COUNT(go) > 0 FROM GroupOrder go
        WHERE go.status NOT IN :finished
          AND (go.host.id = :userId
               OR EXISTS (SELECT p.id FROM Participation p WHERE p.groupOrder = go AND p.user.id = :userId))
        """)
    boolean existsUnfinishedFor(@Param("userId") Long userId,
                                @Param("finished") Collection<GroupOrderStatus> finished);

    // 방 목록 조회용 쿼리
    @Query("""
        SELECT go FROM GroupOrder go
        JOIN FETCH go.host
        JOIN FETCH go.pickupSpot ps
        WHERE go.status = com.bandal.grouporder.GroupOrderStatus.RECRUITING
        AND ps.university.id = :universityId
        AND go.deadlineAt > :now
        AND go.participantCount < go.capacity
        ORDER BY go.deadlineAt ASC
        """)
    Slice<GroupOrder> findRecruiting(@Param("universityId") Long universityId,
                                     @Param("now") Instant now,
                                     Pageable pageable);

    // 스케줄러가 넘길 후보의 id. 모집중은 마감 시각으로, 나머지는 그 상태가 된 시각으로 고른다
    // 기준 시각(~Before)은 서비스 쪽에서 now에서 기간을 빼서 넘긴다. 최종 판단은 엔티티가 다시 한다
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
