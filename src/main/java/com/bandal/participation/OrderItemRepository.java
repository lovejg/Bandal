package com.bandal.participation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    // 한 참여자가 담은 메뉴들
    List<OrderItem> findByParticipationId(Long participationId);

    // 방 전체의 메뉴 금액 합계
    // 아무도 메뉴를 담지 않은 방의 경우, SUM을 했을 때 NULL을 뱉는다(더할 행이 없으니까).
    // 근데 해당 NULL이 자바로 넘어올 때 반환 타입이 기본형 long이라서 에러가 터지므로 COALESCE를 사용해야 된다.
    // COALESCE(a, b)는 "a가 NULL이면 b다"라는 뜻.
    @Query("""
        SELECT COALESCE(SUM(oi.unitPrice * oi.quantity), 0) from OrderItem oi
        WHERE oi.participation.groupOrder.id = :groupOrderId
        """)
    long sumAmountByGroupOrderId(@Param("groupOrderId") Long groupOrderId);
}
