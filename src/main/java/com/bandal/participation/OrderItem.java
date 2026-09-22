package com.bandal.participation;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

    @Column(nullable = false, length = 100)
    private String menuName;

    // 맵기, 사이드 추가 같은 옵션. 구조 없는 자유 문자열이고 없으면 null
    @Column(length = 200)
    private String options;

    // 옵션 금액까지 포함한 한 개 값
    private long unitPrice;

    private int quantity;

    OrderItem(Participation participation, String menuName, String options, long unitPrice, int quantity) {
        if(participation == null) {
            throw new IllegalArgumentException("참여 정보 없이 메뉴를 만들 수 없습니다");
        }
        if(menuName == null || menuName.isBlank()) {
            throw new IllegalArgumentException("메뉴 이름을 적어주세요");
        }
        if(unitPrice <= 0) {
            throw new IllegalArgumentException("가격은 0원보다 커야 합니다");
        }
        if(quantity < 1) {
            throw new IllegalArgumentException("개수는 1개 이상이어야 합니다");
        }

        this.participation = participation;
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
