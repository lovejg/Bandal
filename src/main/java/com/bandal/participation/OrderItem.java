package com.bandal.participation;

import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

// 참여자가 담은 메뉴 한 줄. 가게 연동이 없어서 이름과 가격을 직접 입력받는다 (ADR-022)
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "participation_id", nullable = false)
    private Participation participation;

    // 길이 상한은 요청 DTO와 맞춘다 (ADR-038)
    @Column(nullable = false, length = 50)
    private String menuName;

    // 맵기, 사이드 추가 같은 옵션. 구조 없는 자유 문자열이고 없으면 null
    // 방장이 배달앱에서 그대로 따라 누를 수 있을 만큼 적는다 (ADR-038)
    @Column(length = 100)
    private String options;

    // 옵션 금액까지 포함한 한 개 값
    private long unitPrice;

    private int quantity;

    // 마감 후 방장이 남의 메뉴를 고쳤으면 true. 참여자 정산 화면에 "방장이 수정함"으로 보여준다
    private boolean editedByHost;

    OrderItem(Participation participation, String menuName, String options, long unitPrice, int quantity) {
        if(participation == null) {
            throw new IllegalArgumentException("참여 정보 없이 메뉴를 만들 수 없습니다");
        }
        this.participation = participation;
        applyValues(menuName, options, unitPrice, quantity);
    }

    // 메뉴 고치기. 이름, 옵션, 가격, 개수를 통째로 바꾼다
    // 모집중(마감 시각 전)에는 주인만, 마감(CLOSED)에는 방장만 고칠 수 있다
    public void update(Long requesterId, String menuName, String options, long unitPrice, int quantity, Instant now) {
        GroupOrder groupOrder = this.participation.getGroupOrder();
        Long ownerId = this.participation.getUser().getId();
        Long hostId = groupOrder.getHost().getId();

        // 시각이 아니라 상태로 먼저 나눈다. 방장이 마감 시각 전에 손으로 마감하는 경우가 있다
        if(groupOrder.getStatus() == GroupOrderStatus.RECRUITING) {
            // 스케줄러가 없어서 마감 시각이 지나도 모집중으로 남을 수 있다. 그때는 아무도 못 고친다
            if(!now.isBefore(groupOrder.getDeadlineAt())) {
                throw new IllegalStateException("이미 마감되었습니다");
            }
            if(!Objects.equals(requesterId, ownerId)) {
                throw new IllegalStateException("본인 메뉴만 변경 가능합니다");
            }
        }
        else if(groupOrder.getStatus() == GroupOrderStatus.CLOSED) {
            if(!Objects.equals(requesterId, hostId)) {
                throw new IllegalStateException("마감 이후에는 방장만 메뉴 변경이 가능합니다");
            }
        }
        // 정산중부터, 취소된 방
        else {
            throw new IllegalStateException("메뉴를 바꿀 수 없는 방입니다");
        }

        applyValues(menuName, options, unitPrice, quantity);

        // 고친 사람이 메뉴 주인이 아닐 때만 켠다. 위 검사를 통과했으면 그건 마감 후 방장뿐이다
        if(!Objects.equals(requesterId, ownerId)) {
            this.editedByHost = true;
        }
    }


    private void applyValues(String menuName, String options, long unitPrice, int quantity) {
        if(menuName == null || menuName.isBlank()) {
            throw new IllegalArgumentException("메뉴 이름을 적어주세요");
        }
        if(unitPrice <= 0) {
            throw new IllegalArgumentException("가격은 0원보다 커야 합니다");
        }
        if(quantity < 1) {
            throw new IllegalArgumentException("개수는 1개 이상이어야 합니다");
        }

        this.menuName = menuName;
        if(options == null || options.isBlank()) options = null;
        this.options = options;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }

    public long getAmount() {
        return this.unitPrice * this.quantity;
    }
}
