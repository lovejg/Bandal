package com.bandal.participation;

import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderStatus;
import com.bandal.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

// 방 참여. 방장도 한 행을 가진다. 이탈하면 행을 지운다
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"group_order_id", "user_id"})
    }
)
public class Participation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_order_id", nullable = false)
    private GroupOrder groupOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Instant joinedAt;

    public Participation(GroupOrder groupOrder, User user, Instant joinedAt) {
        this.groupOrder = groupOrder;
        this.user = user;
        this.joinedAt = joinedAt;
    }

    public OrderItem addItem(Long requesterId, String menuName, String options, long unitPrice, int quantity) {
        if(this.groupOrder.getStatus() != GroupOrderStatus.RECRUITING || !Objects.equals(requesterId, this.user.getId())) {
            throw new IllegalStateException("에러 발생");
        }
        return new OrderItem(this, menuName, options, unitPrice, quantity);
    }

    public void removeItem(Long requesterId, OrderItem item) {
        if(this.groupOrder.getStatus() != GroupOrderStatus.RECRUITING || !Objects.equals(requesterId, this.user.getId())
        || !Objects.equals(item.getParticipation().getId(), this.id)) {
            throw new IllegalStateException("에러 발생");
        }
    }
}
