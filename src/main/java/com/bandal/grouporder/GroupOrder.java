package com.bandal.grouporder;

import com.bandal.common.ForbiddenException;
import com.bandal.pickupspot.PickupSpot;
import com.bandal.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
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

    // 배달비. 방장이 정산을 시작할 때 입력한다. null이면 아직 정산 전
    private Long deliveryFee;

    // 방장이 배달앱에서 실제로 결제한 금액. 주문완료 때 받는다. 통계용이고 정산식에는 쓰지 않는다 (ADR-042)
    private Long totalPaidAmount;

    // 취소된 방에만 값이 있다. 방장이 모집중에 취소하면 사유 없이 null일 수도 있다
    private String cancelReason;

    // 취소된 방에만 값이 있다. 누가, 어느 상태에서 취소했는지
    @Enumerated(EnumType.STRING)
    private CancelType cancelType;

    // 아래 다섯은 그 상태가 된 시각이다. 그 상태를 거치지 않았으면 null
    // 스케줄러가 "이 상태로 너무 오래 멈춰 있는가"를 이걸로 잰다
    private Instant closedAt;

    private Instant settlementStartedAt;

    private Instant orderedAt;

    private Instant deliveredAt;

    private Instant canceledAt;

    // 이 상태로 이만큼 멈춰 있으면 스케줄러가 넘긴다
    // 엔티티가 다시 확인할 때와 서비스가 후보를 고르는 쿼리가 같은 숫자를 쓴다
    public static final Duration CLOSED_EXPIRY = Duration.ofHours(3);
    public static final Duration SETTLING_EXPIRY = Duration.ofHours(24);
    public static final Duration ORDERED_EXPIRY = Duration.ofHours(6);

    public GroupOrder(User host, PickupSpot pickupSpot, String storeName,
                      long minOrderAmount, Instant deadlineAt, int capacity) {
        if(host == null) throw new IllegalArgumentException("방장 정보가 없습니다");
        if(!host.hasAccount()) throw new IllegalStateException("방장의 계좌 정보가 없습니다");
        if(pickupSpot == null) throw new IllegalArgumentException("수령지 정보가 없습니다");
        if(!Objects.equals(host.getUniversity().getId(), pickupSpot.getUniversity().getId())) {
            throw new IllegalArgumentException("대학교가 다릅니다");
        }
        this.host = host;
        this.pickupSpot = pickupSpot;
        this.storeName = storeName;
        this.minOrderAmount = minOrderAmount;
        this.deadlineAt = deadlineAt;
        this.capacity = capacity;
        this.status = GroupOrderStatus.RECRUITING;
    }

    public boolean isHost(Long userId) {
        return Objects.equals(userId, this.host.getId());
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

    // 방장이 직접 마감. 조건에 안 맞으면 사유를 담아 거절한다. 마감 시각이 지나면 자동 규칙에 맡긴다
    public void closeByHost(Long requesterId, long participantCount, long menuTotalAmount, Instant now) {
        if (status != GroupOrderStatus.RECRUITING) {
            throw new IllegalStateException("모집중인 방만 마감할 수 있습니다");
        }
        if (!isHost(requesterId)) {
            throw new ForbiddenException("방장만 마감할 수 있습니다");
        }
        if(!now.isBefore(this.deadlineAt)) {
            closeAtDeadline(participantCount, menuTotalAmount, now);
            return;
        }
        if (participantCount < 2) {
            throw new IllegalStateException("방장을 포함해 2명 이상이어야 마감할 수 있습니다");
        }
        if (menuTotalAmount < minOrderAmount) {
            throw new IllegalStateException(
                    "최소주문금액에 " + (minOrderAmount - menuTotalAmount) + "원 모자랍니다");
        }

        status = GroupOrderStatus.CLOSED; // 마감
        this.closedAt = now;
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
            this.canceledAt = now;
            return;
        }

        status = GroupOrderStatus.CLOSED; // 마감
        this.closedAt = now;
    }

    // 방장이 방을 취소. reason은 모집중이면 없어도 되고(null), 마감 뒤면 꼭 있어야 한다
    public void cancelByHost(Long requesterId, String reason, Instant now) {
        if (status == GroupOrderStatus.CANCELED) {
            throw new IllegalStateException("이미 취소된 방입니다");
        }
        if (status != GroupOrderStatus.RECRUITING && status != GroupOrderStatus.CLOSED
                && status != GroupOrderStatus.SETTLING) {
            throw new IllegalStateException("이미 주문이 시작된 방은 취소할 수 없습니다");
        }
        if (!isHost(requesterId)) {
            throw new ForbiddenException("방장만 방을 취소할 수 있습니다");
        }
        // 마감 뒤 취소(정산중 포함)는 참여자들에게 피해가 커서 사유를 받는다
        if (status != GroupOrderStatus.RECRUITING && (reason == null || reason.isBlank())) {
            throw new IllegalStateException("마감 뒤에 취소할 때는 사유를 적어야 합니다");
        }

        if(status == GroupOrderStatus.RECRUITING) {
            cancelType = CancelType.HOST_WHILE_RECRUITING;
        }
        else if(status == GroupOrderStatus.CLOSED) {
            cancelType = CancelType.HOST_WHILE_CLOSED;
        }
        else if(status == GroupOrderStatus.SETTLING) {
            cancelType = CancelType.HOST_WHILE_SETTLING;
        }
        cancelReason = reason;
        status = GroupOrderStatus.CANCELED; // 취소
        this.canceledAt = now;
    }

    // 방장이 배달비를 입력해 정산을 시작한다. 마감된 방에서, 방장만 할 수 있다. 결제금액은 주문완료 때 받으므로 없다
    public void startSettlement(Long requesterId, long deliveryFee, Instant now) {
        if(this.status != GroupOrderStatus.CLOSED) {
            throw new IllegalStateException("마감된 방만 정산을 시작할 수 있습니다");
        }
        if(!isHost(requesterId)) {
            throw new ForbiddenException("방장만 배달비를 입력할 수 있습니다");
        }
        if(deliveryFee < 0) {
            throw new IllegalArgumentException("배달비는 0원 이상이어야 합니다");
        }

        this.deliveryFee = deliveryFee;
        status = GroupOrderStatus.SETTLING; // 정산중
        this.settlementStartedAt = now;
    }

    // 정산중에 취소된 방인가(환불을 받는 방)
    public boolean isCanceledWhileSettling() {
        return this.cancelType == CancelType.HOST_WHILE_SETTLING || this.cancelType == CancelType.SETTLING_TIMEOUT;
    }

    // 정산 정보를 보여줘도 되는 상태인지 확인한다
    public void checkSettlementVisible() {
        if(this.status != GroupOrderStatus.SETTLING && this.status != GroupOrderStatus.ORDERED
                && this.status != GroupOrderStatus.DELIVERED && !this.isCanceledWhileSettling()) {
            throw new IllegalStateException("정산이 시작된 방만 정산 정보를 볼 수 있습니다");
        }
    }

    // 방장이 배달비를 고친다. 정산중에서만 받고 상태는 그대로다
    public void changeDeliveryFee(Long requesterId, long deliveryFee) {
        if(this.status != GroupOrderStatus.SETTLING) {
            throw new IllegalStateException("정산중인 방만 배달비를 수정할 수 있습니다");
        }
        if(!isHost(requesterId)) {
            throw new ForbiddenException("방장만 배달비를 입력할 수 있습니다");
        }
        if(deliveryFee < 0) {
            throw new IllegalArgumentException("배달비는 0원 이상이어야 합니다");
        }

        this.deliveryFee = deliveryFee;
    }

    // 방장이 배달앱에서 주문을 마쳤다. 전원 입금 확인은 줄을 모아야 알 수 있어 서비스가 본다
    // 결제금액은 통계용이라 비워도(null) 된다
    public void order(Long requesterId, Long totalPaidAmount, Instant now) {
        if(this.status != GroupOrderStatus.SETTLING) {
            throw new IllegalStateException("정산중인 방만 주문완료로 넘어갈 수 있습니다");
        }
        if(!isHost(requesterId)) {
            throw new ForbiddenException("방장만 주문완료로 넘어갈 수 있습니다");
        }
        if(totalPaidAmount != null && totalPaidAmount < 0) {
            throw new IllegalArgumentException("결제금액은 0원 이상이어야 합니다");
        }
        this.totalPaidAmount = totalPaidAmount;
        this.status = GroupOrderStatus.ORDERED;
        this.orderedAt = now;
    }

    // 음식이 도착했다. 배달앱과 연동돼있는게 아니라서 방장이 눌러야 알 수 있다
    public void deliver(Long requesterId, Instant now) {
        if(this.status != GroupOrderStatus.ORDERED) {
            throw new IllegalStateException("주문완료된 방만 배달완료로 넘어갈 수 있습니다");
        }
        if(!isHost(requesterId)) {
            throw new ForbiddenException("방장만 배달완료로 넘어갈 수 있습니다");
        }
        this.status = GroupOrderStatus.DELIVERED;
        this.deliveredAt = now;
    }

    // 스케줄러가 부른다. 이 상태로 너무 오래 멈춘 방을 넘긴다. 기간이 덜 찼으면 아무것도 안 한다
    // 모집중은 추가 정보(participantCount와 menuTotalAmount)가 필요해서 따로
    public void expireIfStale(Instant now) {
        if(this.status == GroupOrderStatus.CLOSED) {
            if(now.isBefore(this.closedAt.plus(CLOSED_EXPIRY))) return;
            this.cancelType = CancelType.CLOSED_TIMEOUT;
            this.cancelReason = "방장이 3시간 동안 배달비를 입력하지 않았습니다";
            this.status = GroupOrderStatus.CANCELED;
            this.canceledAt = now;
        }
        else if(this.status == GroupOrderStatus.SETTLING) {
            if(now.isBefore(this.settlementStartedAt.plus(SETTLING_EXPIRY))) return;
            this.cancelType = CancelType.SETTLING_TIMEOUT;
            this.cancelReason = "방장이 24시간 동안 주문을 하지 않았습니다";
            this.status = GroupOrderStatus.CANCELED;
            this.canceledAt = now;
        }
        else if(this.status == GroupOrderStatus.ORDERED) {
            if(now.isBefore(this.orderedAt.plus(ORDERED_EXPIRY))) return;
            this.status = GroupOrderStatus.DELIVERED;
            this.deliveredAt = now;
        }
    }
}
