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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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

    // 대학 없는 거점은 이제 생성자가 먼저 막는다(PickupSpotTest). DB까지 갈 일이 없어서 여기서는 보지 않는다

    @Test
    @DisplayName("한 학교의 거점만 꺼낸다")
    void findsByUniversity() {
        University hankuk = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        University minguk = universityRepository.save(new University("민국대학교", "minguk.ac.kr"));
        pickupSpotRepository.save(new PickupSpot(hankuk, "중앙도서관 앞", null));
        pickupSpotRepository.save(new PickupSpot(hankuk, "공대 7호관 앞", null));
        pickupSpotRepository.save(new PickupSpot(minguk, "민국대 정문", null));

        List<PickupSpot> spots = pickupSpotRepository.findByUniversityId(hankuk.getId());

        // 순서는 보지 않는다. 정렬은 서비스가 한다(PickupSpotApiTest에서 확인)
        assertThat(spots).extracting(PickupSpot::getName)
                .containsExactlyInAnyOrder("중앙도서관 앞", "공대 7호관 앞");
    }

    @Test
    @DisplayName("거점이 없는 학교면 빈 목록이다")
    void emptyWhenNoSpots() {
        University university = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));

        assertThat(pickupSpotRepository.findByUniversityId(university.getId())).isEmpty();
    }

    @Test
    @DisplayName("같은 학교에 비교용 이름이 같은 거점이 있으면 있다고 답한다")
    void existsBySameNormalizedName() {
        University university = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        pickupSpotRepository.save(new PickupSpot(university, "공대 7호관 앞", null));

        assertThat(pickupSpotRepository.existsByUniversityIdAndNormalizedName(university.getId(), "공대7호관앞"))
                .isTrue();
        assertThat(pickupSpotRepository.existsByUniversityIdAndNormalizedName(university.getId(), "중앙도서관앞"))
                .isFalse();
    }

    @Test
    @DisplayName("다른 학교에 같은 이름이 있는 건 상관없다")
    void existsIsPerUniversity() {
        University hankuk = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        University minguk = universityRepository.save(new University("민국대학교", "minguk.ac.kr"));
        pickupSpotRepository.save(new PickupSpot(minguk, "공대 7호관 앞", null));

        assertThat(pickupSpotRepository.existsByUniversityIdAndNormalizedName(hankuk.getId(), "공대7호관앞"))
                .isFalse();
    }

    @Test
    @DisplayName("같은 학교에 띄어쓰기만 다른 이름을 저장하면 DB가 거부한다")
    void uniqueConstraintRejectsDuplicate() {
        // 서비스 확인을 건너뛴 상황이다. 두 사람이 동시에 만들면 실제로 이렇게 된다 (ADR-036)
        University university = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        pickupSpotRepository.saveAndFlush(new PickupSpot(university, "공대 7호관 앞", null));

        // saveAndFlush: 저장하고 바로 DB에 INSERT를 보낸다. 제약조건 위반이 이 자리에서 터진다.
        assertThatThrownBy(() -> pickupSpotRepository.saveAndFlush(new PickupSpot(university, "공대7호관 앞", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("다른 학교라면 같은 이름도 저장된다")
    void uniqueConstraintIsPerUniversity() {
        University hankuk = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        University minguk = universityRepository.save(new University("민국대학교", "minguk.ac.kr"));
        pickupSpotRepository.saveAndFlush(new PickupSpot(hankuk, "공대 7호관 앞", null));

        assertThatCode(() -> pickupSpotRepository.saveAndFlush(new PickupSpot(minguk, "공대 7호관 앞", null)))
                .doesNotThrowAnyException();
    }
}
