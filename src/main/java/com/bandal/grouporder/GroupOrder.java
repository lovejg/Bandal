package com.bandal.grouporder;

import com.bandal.pickupspot.PickupSpot;
import com.bandal.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

// 공구방. 같은 거점에서 같은 가게에 함께 주문한다.
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_id", nullable = false)
    private User host;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pickup_spot_id", nullable = false)
    private PickupSpot pickupSpot;

    @Column(nullable = false)
    private String storeName;

    private long minOrderAmount;

    @Column(nullable = false)
    private Instant deadlineAt;

    // 정원(방장 포함)
    private int capacity;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private GroupOrderStatus status;

    // 배달비. 주문완료 때 방장이 입력한다. null이면 아직 주문 전
    private Long deliveryFee;

    // 방장이 배달앱에서 실제로 결제한 금액. 통계용이고 정산식에는 쓰지 않는다
    private Long totalPaidAmount;

    // 취소된 방에만 값이 있다. 방장이 모집중에 취소하면 사유 없이 null일 수도 있다
    private String cancelReason;

    // 취소된 방에만 값이 있다. 누가, 어느 상태에서 취소했는지
    @Enumerated(EnumType.STRING)
    private CancelType cancelType;

    public GroupOrder(User host, PickupSpot pickupSpot, String storeName,
                      long minOrderAmount, Instant deadlineAt, int capacity) {
        this.host = host;
        this.pickupSpot = pickupSpot;
        this.storeName = storeName;
        this.minOrderAmount = minOrderAmount;
        this.deadlineAt = deadlineAt;
        this.capacity = capacity;
        this.status = GroupOrderStatus.RECRUITING;
    }

    // 이 사람이 지금 이 방에 들어와도 되는지
    public void checkJoinable(User user, long participantCount, Instant now) {
        if(this.status != GroupOrderStatus.RECRUITING) {
            throw new IllegalStateException("모집이 끝난 방입니다");
        }
        else if(!now.isBefore(deadlineAt)) {
            throw new IllegalStateException("마감 시각이 지났습니다");
        }
        else if(participantCount >= capacity) {
            throw new IllegalStateException("정원이 다 찼습니다");
        }
        else if(!Objects.equals(user.getUniversity().getId(), this.pickupSpot.getUniversity().getId())) {
            throw new IllegalStateException("다른 학교의 수령 거점입니다");
        }
    }

    // 방장이 직접 마감. 조건에 안 맞으면 사유를 담아 거절한다
    public void closeByHost(Long requesterId, long participantCount, long menuTotalAmount) {
        if (status != GroupOrderStatus.RECRUITING) {
            throw new IllegalStateException("모집중인 방만 마감할 수 있습니다");
        }
        if (!Objects.equals(requesterId, host.getId())) {
            throw new IllegalStateException("방장만 마감할 수 있습니다");
        }
        if (participantCount < 2) {
            throw new IllegalStateException("방장을 포함해 2명 이상이어야 마감할 수 있습니다");
        }
        if (menuTotalAmount < minOrderAmount) {
            // 얼마나 모자란지까지 알려준다. 조건을 쪼갠 덕에 계산할 값이 손에 있다
            throw new IllegalStateException(
                    "최소주문금액에 " + (minOrderAmount - menuTotalAmount) + "원 모자랍니다");
        }

        status = GroupOrderStatus.CLOSED; // 마감
    }

    // 마감 시각이 돼서 마감. 조건에 안 맞으면 취소
    public void closeAtDeadline(long participantCount, long menuTotalAmount, Instant now) {
        if (status != GroupOrderStatus.RECRUITING) {
            throw new IllegalStateException("모집중인 방만 마감 처리할 수 있습니다");
        }
        if (now.isBefore(deadlineAt)) {
            throw new IllegalStateException("아직 마감 시각 전입니다");
        }

        if (participantCount < 2) { // 방장 포함 인원이 2명 미만인 경우
            cancelReason = "인원 부족";
        } else if(menuTotalAmount < minOrderAmount) { // 최소 주문 금액을 채우지 못한 경우
            cancelReason = "최소 주문 금액 채우지 못함";
        }

        if(cancelReason != null) {
            status = GroupOrderStatus.CANCELED;
            cancelType = CancelType.DEADLINE_UNMET;
            return;
        }

        status = GroupOrderStatus.CLOSED; // 마감
    }

    // 방장이 방을 취소. reason은 모집중이면 없어도 되고(null), 마감 뒤면 꼭 있어야 한다
    public void cancelByHost(Long requesterId, String reason) {
        if (status == GroupOrderStatus.CANCELED) {
            throw new IllegalStateException("이미 취소된 방입니다");
        }
        if (status != GroupOrderStatus.RECRUITING && status != GroupOrderStatus.CLOSED) {
            throw new IllegalStateException("이미 주문이 시작된 방은 취소할 수 없습니다");
        }
        if (!Objects.equals(requesterId, host.getId())) {
            throw new IllegalStateException("방장만 방을 취소할 수 있습니다");
        }
        // 마감 뒤 취소는 참여자들에게 피해가 커서 사유를 받는다 (ADR-021)
        if (status == GroupOrderStatus.CLOSED && (reason == null || reason.isBlank())) {
            throw new IllegalStateException("마감 뒤에 취소할 때는 사유를 적어야 합니다");
        }

        if(status == GroupOrderStatus.RECRUITING) {
            cancelType = CancelType.HOST_WHILE_RECRUITING;
        }
        else if(status == GroupOrderStatus.CLOSED) {
            cancelType = CancelType.HOST_AFTER_CLOSED;
        }
        cancelReason = reason;
        status = GroupOrderStatus.CANCELED; // 취소
    }
}
