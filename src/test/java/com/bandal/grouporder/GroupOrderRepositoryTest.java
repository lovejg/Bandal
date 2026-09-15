package com.bandal.grouporder;

import com.bandal.TestcontainersConfiguration;
import com.bandal.pickupspot.PickupSpot;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class GroupOrderRepositoryTest {

    // DB는 마이크로초까지만 저장해서, 그보다 정밀한 시각은 꺼낼 때 잘려 있을 수 있다.
    // 비교가 흔들리지 않게 고정된 시각을 쓴다.
    static final Instant DEADLINE = Instant.parse("2026-09-15T10:30:00Z");

    @Autowired
    GroupOrderRepository groupOrderRepository;

    @Autowired
    UniversityRepository universityRepository;

    @Autowired
    PickupSpotRepository pickupSpotRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    EntityManager entityManager;

    User host;
    PickupSpot pickupSpot;

    // 모든 테스트가 대학, 거점, 방장을 깔고 시작한다
    @BeforeEach
    void setUp() {
        University university = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        pickupSpot = pickupSpotRepository.save(new PickupSpot(university, "제1기숙사 로비", null));
        host = userRepository.save(new User(university, "kim@hankuk.ac.kr", "hashed-password", "배고파"));
    }

    @Test
    @DisplayName("방을 저장하고 다시 꺼내면 입력한 값이 그대로다")
    void saveAndFindById() {
        // given
        GroupOrder groupOrder = new GroupOrder(host, pickupSpot, "○○마라탕", 15_000, DEADLINE, 4);

        // when
        GroupOrder saved = groupOrderRepository.save(groupOrder);
        entityManager.flush();
        entityManager.clear();

        GroupOrder found = groupOrderRepository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(found.getHost().getId()).isEqualTo(host.getId());
        assertThat(found.getPickupSpot().getId()).isEqualTo(pickupSpot.getId());
        assertThat(found.getStoreName()).isEqualTo("○○마라탕");
        assertThat(found.getMinOrderAmount()).isEqualTo(15_000);
        assertThat(found.getDeadlineAt()).isEqualTo(DEADLINE);
        assertThat(found.getCapacity()).isEqualTo(4);
    }

    @Test
    @DisplayName("새로 만든 방은 모집중이고 배달비, 결제금액, 취소 사유가 비어 있다")
    void initialState() {
        GroupOrder saved = groupOrderRepository.save(new GroupOrder(host, pickupSpot, "○○마라탕", 15_000, DEADLINE, 4));
        entityManager.flush();
        entityManager.clear();

        GroupOrder found = groupOrderRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(GroupOrderStatus.RECRUITING);
        assertThat(found.getDeliveryFee()).isNull();
        assertThat(found.getTotalPaidAmount()).isNull();
        assertThat(found.getCancelReason()).isNull();
    }

    @Test
    @DisplayName("상태는 DB에 순서 번호가 아니라 이름으로 저장된다")
    void statusIsStoredAsName() {
        GroupOrder saved = groupOrderRepository.saveAndFlush(new GroupOrder(host, pickupSpot, "○○마라탕", 15_000, DEADLINE, 4));

        // JPA를 거치지 않고 SQL로 컬럼 값을 직접 읽는다.
        // 순서 번호로 저장됐다면 "RECRUITING" 대신 0이 나온다.
        Object stored = entityManager
                .createNativeQuery("select status from group_order where id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();

        assertThat(stored).isEqualTo("RECRUITING");
    }

    @Test
    @DisplayName("방을 조회해도 방장과 거점은 바로 가져오지 않는다")
    void associationsAreLazy() {
        GroupOrder saved = groupOrderRepository.save(new GroupOrder(host, pickupSpot, "○○마라탕", 15_000, DEADLINE, 4));
        entityManager.flush();
        entityManager.clear();

        GroupOrder found = groupOrderRepository.findById(saved.getId()).orElseThrow();

        assertThat(Hibernate.isInitialized(found.getHost())).isFalse();
        assertThat(Hibernate.isInitialized(found.getPickupSpot())).isFalse();
    }

    @Test
    @DisplayName("방장 없이 방을 저장하면 DB가 거부한다")
    void hostIsRequired() {
        GroupOrder groupOrder = new GroupOrder(null, pickupSpot, "○○마라탕", 15_000, DEADLINE, 4);

        assertThatThrownBy(() -> groupOrderRepository.saveAndFlush(groupOrder))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("거점 없이 방을 저장하면 DB가 거부한다")
    void pickupSpotIsRequired() {
        GroupOrder groupOrder = new GroupOrder(host, null, "○○마라탕", 15_000, DEADLINE, 4);

        assertThatThrownBy(() -> groupOrderRepository.saveAndFlush(groupOrder))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("가게 이름 없이 방을 저장하면 DB가 거부한다")
    void storeNameIsRequired() {
        GroupOrder groupOrder = new GroupOrder(host, pickupSpot, null, 15_000, DEADLINE, 4);

        assertThatThrownBy(() -> groupOrderRepository.saveAndFlush(groupOrder))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("마감 시각 없이 방을 저장하면 DB가 거부한다")
    void deadlineAtIsRequired() {
        GroupOrder groupOrder = new GroupOrder(host, pickupSpot, "○○마라탕", 15_000, null, 4);

        assertThatThrownBy(() -> groupOrderRepository.saveAndFlush(groupOrder))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
