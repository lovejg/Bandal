package com.bandal.pickupspot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PickupSpotRepository extends JpaRepository<PickupSpot, Long> {
    Optional<PickupSpot> findById(Long id);

    // 한 학교의 거점 전부. 페이징은 하지 않는다 (ADR-036)
    // 정렬은 여기서 하지 않는다. DB 기본 정렬 규칙(en_US)이 한글을 가나다 반대로 세워서, 서비스가 자바에서 한다
    List<PickupSpot> findByUniversityId(Long universityId);

    // 같은 학교에 비교용 이름이 같은 거점이 이미 있는지. 거점을 만들기 전에 확인한다
    boolean existsByUniversityIdAndNormalizedName(Long universityId, String normalizedName);
}
