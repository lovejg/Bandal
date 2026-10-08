package com.bandal.settlement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    List<Settlement> findByGroupOrderId(Long groupOrderId);

    Optional<Settlement> findByGroupOrderIdAndUserId(Long groupOrderId, Long userId);

    // 주문완료로 넘어갈 수 있는지. 0이어야 한다
    long countByGroupOrderIdAndConfirmedPaidAtIsNull(Long groupOrderId);

    // 배달비 수정 때 정산표를 통째로 지운다
    // 이름으로만 만들면 하나씩 불러와서 하나씩 지운다. DELETE 한 번으로 보낸다
    // flushAutomatically: DELETE 전에 쌓여 있던 변경(방의 배달비)을 먼저 DB에 보낸다
    // clearAutomatically는 쓰지 않는다. 보관함을 비우면 같은 트랜잭션에서 읽어둔 방과 방장도 관리 대상에서 빠진다.
    // 응답을 만들 때 방장 계좌를 읽어야 해서 그대로 둔다
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM Settlement s WHERE s.groupOrder.id = :groupOrderId")
    int deleteByGroupOrderId(@Param("groupOrderId") Long groupOrderId);
}
