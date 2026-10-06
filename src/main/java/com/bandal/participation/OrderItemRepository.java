package com.bandal.participation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    Optional<OrderItem> findById(Long id);

    void deleteById(Long id);

    // 한 참여자가 담은 메뉴들
    List<OrderItem> findByParticipationId(Long participationId);

    // 한 방의 메뉴 전부. 검수 목록이 사람마다 묻지 않고 한 번에 가져온다
    // 누구 메뉴인지는 서비스가 oi.getParticipation().getId()로 나눈다. 프록시라도 id는 쿼리 없이 나온다
    // 담은 순서대로 보이게 id로 정렬한다
    @Query("""
        SELECT oi FROM OrderItem oi
        WHERE oi.participation.groupOrder.id = :groupOrderId
        ORDER BY oi.id
        """)
    List<OrderItem> findByGroupOrderId(@Param("groupOrderId") Long groupOrderId);

    // 방 전체의 메뉴 금액 합계
    // 아무도 메뉴를 담지 않은 방의 경우, SUM을 했을 때 NULL을 뱉는다(더할 행이 없으니까).
    // 근데 해당 NULL이 자바로 넘어올 때 반환 타입이 기본형 long이라서 에러가 터지므로 COALESCE를 사용해야 된다.
    // COALESCE(a, b)는 "a가 NULL이면 b다"라는 뜻.
    @Query("""
        SELECT COALESCE(SUM(oi.unitPrice * oi.quantity), 0) from OrderItem oi
        WHERE oi.participation.groupOrder.id = :groupOrderId
        """)
    long sumAmountByGroupOrderId(@Param("groupOrderId") Long groupOrderId);

    // 한 참여자가 담은 메뉴 금액 합계. 정산표를 만들 때 사람마다 부른다
    // 참여자 수만큼 쿼리가 나가지만 그 수는 capacity로 묶여 있어서 몇 개다.
    // 한 번에 가져오려면 GROUP BY와 별도 반환 타입이 필요한데 지금은 과하다
    @Query("""
        SELECT COALESCE(SUM(oi.unitPrice * oi.quantity), 0) from OrderItem oi
        WHERE oi.participation.id = :participationId
        """)
    long sumAmountByParticipationId(@Param("participationId") Long participationId);

    // 방 목록 조회에서 사용. 한 번에 조건에 맞는 방의 메뉴 총 금액 합계를 모두 가져옴으로써 N+1 문제를 방지
    @Query("""
        SELECT new com.bandal.participation.GroupOrderStat(oi.participation.groupOrder.id, SUM(oi.unitPrice * oi.quantity))
        FROM OrderItem oi
        WHERE oi.participation.groupOrder.id IN :groupOrderIds
        GROUP BY oi.participation.groupOrder.id
            """)
    List<GroupOrderStat> sumAmountByGroupOrderIds(@Param("groupOrderIds") List<Long> groupOrderIds);

    // 한 참여자의 메뉴를 전부 지운다
    // 메뉴가 참여 행을 외래 키로 가리켜서, 메뉴가 남아 있으면 참여 행을 지울 수 없다
    // @Modifying는 DELETE, UPDATE를 @Query로 쓸 때 꼭 붙인다
    // 돌려주는 int는 지워진 행 수
    // 이 DELETE는 영속성 컨텍스트를 거치지 않고 DB로 바로 간다.
    // 그래서 보관함에 이미 올라와 있던 메뉴 객체는 "아직 살아 있다"고 착각한 채 남는다.
    // 그 상태로 참여 행을 지우면, 남은 메뉴 객체가 지워진 참여를 가리켜서 flush 때 터진다
    // flushAutomatically: DELETE 전에 쌓여 있던 변경을 먼저 DB에 보낸다. 아래 clear로 잃지 않게
    // clearAutomatically: DELETE 뒤에 보관함을 비운다. 낡은 메뉴 객체가 남지 않는다
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM OrderItem oi WHERE oi.participation.id = :participationId")
    int deleteByParticipationId(@Param("participationId") Long participationId);
}
