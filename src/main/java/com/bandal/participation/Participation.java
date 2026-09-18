package com.bandal.participation;

import com.bandal.grouporder.GroupOrder;
import com.bandal.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

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
}
