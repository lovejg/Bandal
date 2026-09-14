package com.bandal.pickupspot;

import com.bandal.university.University;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // 기본 생성자(protected)
public class PickupSpot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "university_id", nullable = false)
    private University university;

    // 위치는 일단은 실제 좌표 말고 String(어차피 캠퍼스 내 혹은 근처 장소니까 꼭 좌표가 아니라 String으로 표현해도 된다)
    // 다만 정보가 부족한 인원이 있을 수 있기 때문에 추가적으로 아래 description 필드가 같이 있다
    private String name;

    private String description;

    public PickupSpot(University university, String name, String description) {
        this.university = university;
        this.name = name;
        this.description = description;
    }
}
