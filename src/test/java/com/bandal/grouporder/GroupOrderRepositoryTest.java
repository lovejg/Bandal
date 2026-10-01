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
        // 계좌가 없으면 방을 만들 수 없다 (ADR-031)
        host.registerAccount("한국은행", "110-123-456789", "김민수");
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
        assertThat(found.getCancelType()).isNull();
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
    @DisplayName("취소 종류도 DB에 이름으로 저장되고, 다시 꺼내면 그대로다")
    void cancelTypeIsStoredAsName() {
        GroupOrder groupOrder = new GroupOrder(host, pickupSpot, "○○마라탕", 15_000, DEADLINE, 4);
        groupOrder.cancelByHost(host.getId(), null);
        GroupOrder saved = groupOrderRepository.saveAndFlush(groupOrder);

        Object stored = entityManager
                .createNativeQuery("select cancel_type from group_order where id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();
        assertThat(stored).isEqualTo("HOST_WHILE_RECRUITING");

        entityManager.clear();
        GroupOrder found = groupOrderRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getCancelType()).isEqualTo(CancelType.HOST_WHILE_RECRUITING);
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
    @DisplayName("방장 없이는 방을 만들 수조차 없다")
    void hostIsRequired() {
        // 예전에는 DB의 NOT NULL 제약이 막았다. 이제는 생성자가 먼저 막는다.
        // 생성자가 방장의 계좌를 봐야 해서 방장이 없으면 거기서 걸린다.
        // DB 제약은 그대로 남아 두 번째 방어선이 된다
        assertThatThrownBy(() -> new GroupOrder(null, pickupSpot, "○○마라탕", 15_000, DEADLINE, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("거점 없이는 방을 만들 수조차 없다")
    void pickupSpotIsRequired() {
        // 방장과 같은 이유로 바뀌었다. 생성자가 거점의 학교를 방장의 학교와 비교해야 해서(ADR-036)
        // 거점이 없으면 거기서 걸린다. DB 제약은 두 번째 방어선으로 남는다
        assertThatThrownBy(() -> new GroupOrder(host, null, "○○마라탕", 15_000, DEADLINE, 4))
                .isInstanceOf(IllegalArgumentException.class);
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
