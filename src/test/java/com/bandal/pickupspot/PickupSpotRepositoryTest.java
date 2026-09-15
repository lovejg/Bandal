package com.bandal.pickupspot;

import com.bandal.TestcontainersConfiguration;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class PickupSpotRepositoryTest {

    @Autowired
    PickupSpotRepository pickupSpotRepository;

    @Autowired
    UniversityRepository universityRepository;

    @Autowired
    EntityManager entityManager;

    @Test
    @DisplayName("거점을 저장하고 다시 꺼내면 이름, 설명, 소속 대학이 그대로다")
    void saveAndFindById() {
        // given
        University university = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        PickupSpot spot = new PickupSpot(university, "제1기숙사 로비", "정문 쪽 출입구");

        // when
        PickupSpot saved = pickupSpotRepository.save(spot);
        entityManager.flush();
        entityManager.clear();

        PickupSpot found = pickupSpotRepository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(found.getName()).isEqualTo("제1기숙사 로비");
        assertThat(found.getDescription()).isEqualTo("정문 쪽 출입구");
        assertThat(found.getUniversity().getId()).isEqualTo(university.getId());
    }

    @Test
    @DisplayName("거점을 조회해도 소속 대학은 바로 가져오지 않는다")
    void universityIsLazy() {
        // given
        University university = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        PickupSpot saved = pickupSpotRepository.save(new PickupSpot(university, "공학관 앞", null));
        entityManager.flush();
        entityManager.clear();

        // when
        PickupSpot found = pickupSpotRepository.findById(saved.getId()).orElseThrow();

        // then
        // LAZY면 university 자리에 진짜 대학 대신 프록시(나중에 조회할 대리 객체)가 들어 있다.
        // isInitialized가 false면 아직 대학 SELECT가 안 나갔다는 뜻이다.
        assertThat(Hibernate.isInitialized(found.getUniversity())).isFalse();
    }

    @Test
    @DisplayName("대학 없이 거점을 저장하면 DB가 거부한다")
    void universityIsRequired() {
        PickupSpot spot = new PickupSpot(null, "소속 없는 거점", null);

        // saveAndFlush: 저장하고 바로 DB에 INSERT를 보낸다. 제약조건 위반이 이 자리에서 터진다.
        assertThatThrownBy(() -> pickupSpotRepository.saveAndFlush(spot))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
