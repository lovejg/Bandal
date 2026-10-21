package com.bandal.university;

import com.bandal.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
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
    @DisplayName("이메일 도메인 없이 대학을 저장하면 DB가 거부한다")
    void emailDomainIsRequired() {
        University university = new University("도메인 없는 대학교", null);

        // saveAndFlush: 저장하고 바로 DB에 INSERT를 보낸다. 제약조건 위반이 이 자리에서 터진다.
        assertThatThrownBy(() -> universityRepository.saveAndFlush(university))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 학교의 캠퍼스들은 이메일 도메인을 같이 쓸 수 있다")
    void campusesShareEmailDomain() {
        // 캠퍼스 하나가 대학 행 하나다. 신촌과 미래캠퍼스 학생은 둘 다 @yonsei.ac.kr이다. 어느 캠퍼스인지는 가입할 때 고른다
        universityRepository.saveAndFlush(new University("한국대학교 본캠퍼스", "campus-test.ac.kr"));
        University other = new University("한국대학교 제2캠퍼스", "campus-test.ac.kr");

        assertThatCode(() -> universityRepository.saveAndFlush(other)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("이메일 도메인으로 그 학교의 캠퍼스를 모두 찾는다. 순서는 등록한 순서다")
    void findAllByEmailDomain() {
        University main = universityRepository.save(new University("한국대학교 본캠퍼스", "campus-test.ac.kr"));
        University second = universityRepository.save(new University("한국대학교 제2캠퍼스", "campus-test.ac.kr"));
        universityRepository.save(new University("다른대학교", "other.ac.kr"));
        entityManager.flush();
        entityManager.clear();

        List<University> found = universityRepository.findAllByEmailDomainOrderById("campus-test.ac.kr");

        assertThat(found).extracting(University::getId).containsExactly(main.getId(), second.getId());
    }

    @Test
    @DisplayName("등록되지 않은 도메인이면 빈 목록이다")
    void findAllByUnknownEmailDomain() {
        assertThat(universityRepository.findAllByEmailDomainOrderById("gmail.com")).isEmpty();
    }

    @Test
    @DisplayName("운영에 처음 넣는 학교가 마이그레이션으로 들어 있다")
    void seededUniversities() {
        // 운영 DB에는 대학을 만드는 API가 없어서, 마이그레이션이 넣어 두지 않으면 아무도 가입할 수 없다
        List<String> names = entityManager
                .createQuery("select u.name from University u where u.emailDomain in :domains order by u.name", String.class)
                .setParameter("domains", List.of("snu.ac.kr", "yonsei.ac.kr", "korea.ac.kr", "hanyang.ac.kr",
                        "khu.ac.kr", "sogang.ac.kr", "ewhain.net", "kaist.ac.kr", "kw.ac.kr"))
                .getResultList();

        assertThat(names).containsExactlyInAnyOrder(
                "서울대학교",
                "연세대학교 신촌캠퍼스", "연세대학교 미래캠퍼스",
                "고려대학교 서울캠퍼스", "고려대학교 세종캠퍼스",
                "한양대학교 서울캠퍼스", "한양대학교 ERICA캠퍼스",
                "경희대학교 서울캠퍼스", "경희대학교 국제캠퍼스",
                "서강대학교",
                "이화여자대학교",
                "KAIST",
                "광운대학교");
    }
}
