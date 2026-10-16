package com.bandal.settlement;

import com.bandal.participation.GroupOrderStat;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    List<Settlement> findByGroupOrderId(Long groupOrderId);

    Optional<Settlement> findByGroupOrderIdAndUserId(Long groupOrderId, Long userId);

    // 정산 줄 하나. 보냈어요, 받았어요, 확인 취소, 환불 버튼이 쓴다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Settlement s WHERE s.id = :id")
    Optional<Settlement> findByIdForUpdate(@Param("id") Long id);

    // 한 방의 정산 줄 전부. 배달비 수정이 "입금 기록 없음"을 확인할 때 쓴다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Settlement s WHERE s.groupOrder.id = :groupOrderId")
    List<Settlement> findByGroupOrderIdForUpdate(@Param("groupOrderId") Long groupOrderId);

    // 배달비 수정 때 정산표를 통째로 지운다
    // 이름으로만 만들면 하나씩 불러와서 하나씩 지운다. DELETE 한 번으로 보낸다
    // flushAutomatically: DELETE 전에 쌓여 있던 변경(방의 배달비)을 먼저 DB에 보낸다
    // clearAutomatically는 쓰지 않는다. 보관함을 비우면 같은 트랜잭션에서 읽어둔 방과 방장도 관리 대상에서 빠진다.
    // 응답을 만들 때 방장 계좌를 읽어야 해서 그대로 둔다
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM Settlement s WHERE s.groupOrder.id = :groupOrderId")
    int deleteByGroupOrderId(@Param("groupOrderId") Long groupOrderId);

    // 내 방 목록에서 쓴다. 여러 방에 걸친 내 줄을 한 번에 가져온다
    @Query("""
        SELECT s FROM Settlement s
        WHERE s.user.id = :userId
        AND s.groupOrder.id IN :groupOrderIds
        """)
    List<Settlement> findMineByGroupOrderIds(@Param("userId") Long userId,
                                             @Param("groupOrderIds") List<Long> groupOrderIds);

    // 내 방 목록의 방장 칸. 방마다 확인을 기다리는 참여자 수
    @Query("""
        SELECT new com.bandal.participation.GroupOrderStat(s.groupOrder.id, COUNT(s)) FROM Settlement s
        WHERE s.groupOrder.id IN :groupOrderIds
        AND s.groupOrder.status = com.bandal.grouporder.GroupOrderStatus.SETTLING
        AND s.host = false
        AND s.markedPaidAt IS NOT NULL
        AND s.confirmedPaidAt IS NULL
        GROUP BY s.groupOrder.id
        """)
    List<GroupOrderStat> countAwaitingConfirmByGroupOrderIds(@Param("groupOrderIds") List<Long> groupOrderIds);
}
