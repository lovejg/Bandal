package com.bandal.user;

import com.bandal.TestcontainersConfiguration;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
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
class UserRepositoryTest {

    @Autowired
    UserRepository userRepository;

    @Autowired
    UniversityRepository universityRepository;

    @Autowired
    EntityManager entityManager;

    University university;

    // 모든 테스트가 대학 하나를 깔고 시작한다
    @BeforeEach
    void setUp() {
        university = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
    }

    @Test
    @DisplayName("사용자를 저장하고 다시 꺼내면 입력한 값이 그대로다")
    void saveAndFindById() {
        // given
        User user = new User(university, "kim@hankuk.ac.kr", "hashed-password", "배고파");

        // when
        User saved = userRepository.save(user);
        entityManager.flush();
        entityManager.clear();

        User found = userRepository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(found.getUniversity().getId()).isEqualTo(university.getId());
        assertThat(found.getEmail()).isEqualTo("kim@hankuk.ac.kr");
        assertThat(found.getPassword()).isEqualTo("hashed-password");
        assertThat(found.getNickname()).isEqualTo("배고파");
    }

    @Test
    @DisplayName("가입 직후에는 이메일 미인증이고 신뢰도는 50이다")
    void initialState() {
        User saved = userRepository.save(new User(university, "kim@hankuk.ac.kr", "hashed-password", "배고파"));
        entityManager.flush();
        entityManager.clear();

        User found = userRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getEmailVerifiedAt()).isNull();
        assertThat(found.getTrustScore()).isEqualTo(50);
    }

    @Test
    @DisplayName("이메일로 사용자를 찾을 수 있다")
    void findByEmail() {
        User saved = userRepository.save(new User(university, "kim@hankuk.ac.kr", "hashed-password", "배고파"));
        userRepository.save(new User(university, "lee@hankuk.ac.kr", "hashed-password", "치킨"));
        entityManager.flush();
        entityManager.clear();

        User found = userRepository.findByEmail("kim@hankuk.ac.kr").orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("사용자를 조회해도 소속 대학은 바로 가져오지 않는다")
    void universityIsLazy() {
        User saved = userRepository.save(new User(university, "kim@hankuk.ac.kr", "hashed-password", "배고파"));
        entityManager.flush();
        entityManager.clear();

        User found = userRepository.findById(saved.getId()).orElseThrow();

        assertThat(Hibernate.isInitialized(found.getUniversity())).isFalse();
    }

    @Test
    @DisplayName("대학 없이 사용자를 저장하면 DB가 거부한다")
    void universityIsRequired() {
        User user = new User(null, "kim@hankuk.ac.kr", "hashed-password", "배고파");

        assertThatThrownBy(() -> userRepository.saveAndFlush(user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("이메일 없이 저장하면 DB가 거부한다")
    void emailIsRequired() {
        User user = new User(university, null, "hashed-password", "배고파");

        assertThatThrownBy(() -> userRepository.saveAndFlush(user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("비밀번호 없이 저장하면 DB가 거부한다")
    void passwordIsRequired() {
        User user = new User(university, "kim@hankuk.ac.kr", null, "배고파");

        assertThatThrownBy(() -> userRepository.saveAndFlush(user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("닉네임 없이 저장하면 DB가 거부한다")
    void nicknameIsRequired() {
        User user = new User(university, "kim@hankuk.ac.kr", "hashed-password", null);

        assertThatThrownBy(() -> userRepository.saveAndFlush(user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("이미 가입된 이메일로는 다시 저장할 수 없다")
    void emailIsUnique() {
        userRepository.saveAndFlush(new User(university, "kim@hankuk.ac.kr", "hashed-password", "배고파"));

        User duplicated = new User(university, "kim@hankuk.ac.kr", "hashed-password", "다른닉네임");
        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicated))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("다른 사용자가 쓰는 닉네임으로는 저장할 수 없다")
    void nicknameIsUnique() {
        userRepository.saveAndFlush(new User(university, "kim@hankuk.ac.kr", "hashed-password", "배고파"));

        User duplicated = new User(university, "lee@hankuk.ac.kr", "hashed-password", "배고파");
        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicated))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
