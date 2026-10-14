package com.bandal.university;

import com.bandal.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// @DataJpaTest: JPA 관련 빈만 띄운다. 컨트롤러 같은 건 안 올라와서 빠르다.
// 테스트마다 트랜잭션을 열고 끝나면 롤백해서, 테스트끼리 데이터가 섞이지 않는다.
// @Import: Testcontainers로 진짜 Postgres를 띄워서 그 DB에 붙는다.
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class UniversityRepositoryTest {

    @Autowired
    UniversityRepository universityRepository;

    @Autowired
    EntityManager entityManager;

    @Test
    @DisplayName("대학을 저장하고 id로 다시 꺼내면 이름과 이메일 도메인이 그대로다")
    void saveAndFindById() {
        // given
        University university = new University("한국대학교", "hankuk.ac.kr");

        // when
        University saved = universityRepository.save(university);

        // flush: 지금까지 모인 INSERT를 DB로 실제로 보낸다.
        // clear: JPA가 메모리에 들고 있던 엔티티를 비운다.
        // 이걸 안 하면 findById가 DB에 SELECT를 안 날리고 메모리에 있던 같은 객체를 돌려준다.
        // 그러면 "DB에 제대로 저장됐나"를 확인하는 테스트가 아니게 된다.
        entityManager.flush();
        entityManager.clear();

        University found = universityRepository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(saved.getId()).isNotNull();
        assertThat(found.getName()).isEqualTo("한국대학교");
        assertThat(found.getEmailDomain()).isEqualTo("hankuk.ac.kr");
    }

    @Test
    @DisplayName("이메일 도메인으로 대학을 찾을 수 있다")
    void findByEmailDomain() {
        // given
        University university = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        universityRepository.save(new University("다른대학교", "other.ac.kr"));
        entityManager.flush();
        entityManager.clear();

        // when
        University found = universityRepository.findByEmailDomain("hankuk.ac.kr").orElseThrow();

        // then
        assertThat(found.getId()).isEqualTo(university.getId());
    }

    @Test
    @DisplayName("등록되지 않은 도메인으로는 대학을 찾지 못한다")
    void unknownEmailDomain() {
        universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));

        assertThat(universityRepository.findByEmailDomain("gmail.com")).isEmpty();
    }

    @Test
    @DisplayName("이메일 도메인 없이 대학을 저장하면 DB가 거부한다")
    void emailDomainIsRequired() {
        University university = new University("도메인 없는 대학교", null);

        // saveAndFlush: 저장하고 바로 DB에 INSERT를 보낸다. 제약조건 위반이 이 자리에서 터진다.
        assertThatThrownBy(() -> universityRepository.saveAndFlush(university))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("이미 다른 대학이 쓰는 이메일 도메인은 저장할 수 없다")
    void emailDomainIsUnique() {
        // given
        universityRepository.saveAndFlush(new University("한국대학교", "hankuk.ac.kr"));

        // when, then
        // 같은 도메인이 두 학교에 걸리면 가입자가 어느 학교 소속인지 정할 수 없다.
        University duplicated = new University("다른대학교", "hankuk.ac.kr");
        assertThatThrownBy(() -> universityRepository.saveAndFlush(duplicated))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
