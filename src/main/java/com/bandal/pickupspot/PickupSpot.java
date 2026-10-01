package com.bandal.pickupspot;

import com.bandal.university.University;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // 기본 생성자(protected)
// 같은 학교 안에서 비교용 이름이 겹치면 DB가 거부한다. 서비스 확인을 동시에 빠져나간 경우의 마지막 방어선 (ADR-036)
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"university_id", "normalized_name"}))
public class PickupSpot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "university_id", nullable = false)
    private University university;

    // 위치는 일단은 실제 좌표 말고 String(어차피 캠퍼스 내 혹은 근처 장소니까 꼭 좌표가 아니라 String으로 표현해도 된다)
    // 다만 정보가 부족한 인원이 있을 수 있기 때문에 추가적으로 아래 description 필드가 같이 있다
    @Column(nullable = false, length = 30)
    private String name;

    // 중복 비교용. 공백과 특수기호를 빼고 영문은 소문자로. 사용자가 넣는 값이 아니라 name에서 계산한다
    // "공대 7호관 앞", "공대7호관 앞!" 둘 다 "공대7호관앞"이 된다
    @Column(nullable = false, length = 30)
    private String normalizedName;

    @Column(length = 100)
    private String description;

    public PickupSpot(University university, String name, String description) {
        if(university == null) throw new IllegalArgumentException("대학교 정보가 없습니다");
        if(name == null || name.isBlank()) throw new IllegalArgumentException("수령지 이름이 없습니다");
        if(name.length() > 30) throw new IllegalArgumentException("수령지 이름이 30자를 넘습니다");
        if(description != null && description.length() > 100) {
            throw new IllegalArgumentException("수령지 설명이 100자를 넘습니다");
        }
        this.university = university;
        this.name = name;
        this.normalizedName = name.replaceAll("[^a-zA-Z0-9가-힣]", "").toLowerCase();
        if(normalizedName.isBlank()) throw new IllegalArgumentException("수령지 이름 형식이 잘못됐습니다");
        this.description = description;
    }
}
