package com.bandal.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    List<Settlement> findByGroupOrderId(Long groupOrderId);

    Optional<Settlement> findByGroupOrderIdAndUserId(Long groupOrderId, Long userId);

    // 배달비를 다시 입력해도 되는지 판단할 때 쓴다. 한 명이라도 보냈다고 표시했으면 막는다
    long countByGroupOrderIdAndMarkedPaidAtIsNotNull(Long groupOrderId);

    // 주문완료로 넘어갈 수 있는지. 0이어야 한다
    long countByGroupOrderIdAndConfirmedPaidAtIsNull(Long groupOrderId);

    // 배달비 재입력 때 정산표를 통째로 지우고 다시 만든다
    void deleteByGroupOrderId(Long groupOrderId);
}
