package com.bandal.settlement;

import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderStatus;
import com.bandal.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

// 정산표 한 줄. 참여자 한 명이 얼마를 부담하고 입금이 어디까지 왔는지 (ADR-030)
// 금액은 계산으로 다시 구할 수 있지만 입금 여부는 누가 눌러야만 아는 사실이라 저장한다
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_order_id", nullable = false)
    private GroupOrder groupOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 이 사람이 담은 메뉴의 합계
    private long menuTotalAmount;

    // 배달비 분담액. 나누어떨어지지 않는 나머지는 방장이 떠안는다
    private long feeShare;

    private long totalAmount;

    private boolean host;

    // 참여자가 "보냈어요"를 누른 시각
    private Instant markedPaidAt;

    // 방장이 "받았어요"를 누른 시각. 이 값이 주문완료 전이의 기준이다
    private Instant confirmedPaidAt;

    // 방장이 확인을 취소한 마지막 시각. 값이 있으면 확인했다가 되돌린 적이 있다는 뜻이다
    private Instant confirmRevokedAt;

    Settlement(GroupOrder groupOrder, User user, long menuTotalAmount, long feeShare,
               boolean host, Instant now) {
            if(host) {
                markedPaidAt = now;
                confirmedPaidAt = now;
            }
            this.groupOrder = groupOrder;
            this.user = user;
            this.menuTotalAmount = menuTotalAmount;
            this.feeShare = feeShare;
            this.totalAmount = menuTotalAmount + feeShare;
            this.host = host;
    }

    // 참여자가 송금하고 "보냈어요"를 누른다
    public void markPaid(Long requesterId, Instant now) {
        if(this.groupOrder.getStatus() != GroupOrderStatus.SETTLING) {
            throw new IllegalStateException("정산중인 방에서만 할 수 있습니다");
        }
        if(!Objects.equals(this.user.getId(), requesterId)) {
            throw new IllegalStateException("자기 몫만 표시할 수 있습니다");
        }
        else {
            if(confirmedPaidAt != null) {
                throw new IllegalStateException("이미 송금을 완료했습니다");
            }
            if(markedPaidAt != null) {
                return;
            }

            markedPaidAt = now;
        }
    }

    // 방장이 입금을 확인한다.
    public void confirm(Long requesterId, Instant now) {
        if(this.groupOrder.getStatus() != GroupOrderStatus.SETTLING) {
            throw new IllegalStateException("정산중인 방에서만 할 수 있습니다");
        }
        if (!Objects.equals(groupOrder.getHost().getId(), requesterId)) {
            throw new IllegalStateException("방장만 입금을 확인할 수 있습니다");
        }
        if (confirmedPaidAt != null) {
            throw new IllegalStateException("이미 입금 확인이 완료됐습니다");
        }

        confirmedPaidAt = now;
    }

    // 방장이 잘못 누른 확인을 되돌린다. 참여자의 표시는 건드리지 않는다 (ADR-045)
    public void revokeConfirm(Long requesterId, Instant now) {
        if(this.groupOrder.getStatus() != GroupOrderStatus.SETTLING) {
            throw new IllegalStateException("정산중인 방에서만 할 수 있습니다");
        }
        if(!Objects.equals(this.groupOrder.getHost().getId(), requesterId)) {
            throw new IllegalStateException("방장만 확인을 취소할 수 있습니다");
        }
        if(host) {
            throw new IllegalStateException("방장 줄은 확인을 취소할 수 없습니다");
        }
        if(confirmedPaidAt == null) {
            return;
        }

        confirmedPaidAt = null;
        confirmRevokedAt = now;
    }

    // 이 줄에 입금과 관련된 흔적이 하나라도 있는가. 있으면 배달비를 고칠 수 없다
    public boolean hasPaymentRecord() {
        if(host) return false; // 방장 줄은 있을리가 없음
        return markedPaidAt != null || confirmedPaidAt != null || confirmRevokedAt != null;
    }

    // 입금이 정리됐는가
    public boolean isConfirmed() {
        return confirmedPaidAt != null;
    }

    // 보냈다는데 확인이 안 된 상태. 분쟁의 신호다
    public boolean isDisputed() {
        return markedPaidAt != null && confirmedPaidAt == null;
    }
}
