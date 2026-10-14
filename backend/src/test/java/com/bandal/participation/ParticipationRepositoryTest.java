package com.bandal.participation;

import com.bandal.TestcontainersConfiguration;
import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderRepository;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class ParticipationRepositoryTest {

    static final Instant DEADLINE = Instant.parse("2026-09-16T10:30:00Z");
    static final Instant JOINED_AT = Instant.parse("2026-09-16T09:00:00Z");

    @Autowired
    ParticipationRepository participationRepository;

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
    User member;
    GroupOrder groupOrder;
    GroupOrder otherGroupOrder;

    // 대학, 거점, 방장, 참여자 한 명, 방 두 개를 깔고 시작한다
    @BeforeEach
    void setUp() {
        University university = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        PickupSpot pickupSpot = pickupSpotRepository.save(new PickupSpot(university, "제1기숙사 로비", null));
        host = userRepository.save(new User(university, "kim@hankuk.ac.kr", "hashed-password", "배고파"));
        // 계좌가 없으면 방을 만들 수 없다 (ADR-031)
        host.registerAccount("한국은행", "110-123-456789", "김민수");
        member = userRepository.save(new User(university, "lee@hankuk.ac.kr", "hashed-password", "마라탕러버"));
        groupOrder = groupOrderRepository.save(new GroupOrder(host, pickupSpot, "○○마라탕", 15_000, DEADLINE, 4));
        otherGroupOrder = groupOrderRepository.save(new GroupOrder(host, pickupSpot, "△△치킨", 20_000, DEADLINE, 3));
    }

    @Test
    @DisplayName("참여를 저장하고 다시 꺼내면 입력한 값이 그대로다")
    void saveAndFindById() {
        Participation saved = participationRepository.save(new Participation(groupOrder, member, JOINED_AT));
        entityManager.flush();
        entityManager.clear();

        Participation found = participationRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getGroupOrder().getId()).isEqualTo(groupOrder.getId());
        assertThat(found.getUser().getId()).isEqualTo(member.getId());
        assertThat(found.getJoinedAt()).isEqualTo(JOINED_AT);
    }

    @Test
    @DisplayName("참여를 조회해도 방과 사람은 바로 가져오지 않는다")
    void associationsAreLazy() {
        Participation saved = participationRepository.save(new Participation(groupOrder, member, JOINED_AT));
        entityManager.flush();
        entityManager.clear();

        Participation found = participationRepository.findById(saved.getId()).orElseThrow();

        assertThat(Hibernate.isInitialized(found.getGroupOrder())).isFalse();
        assertThat(Hibernate.isInitialized(found.getUser())).isFalse();
    }

    @Test
    @DisplayName("방 없이 참여를 저장하면 DB가 거부한다")
    void groupOrderIsRequired() {
        Participation participation = new Participation(null, member, JOINED_AT);

        assertThatThrownBy(() -> participationRepository.saveAndFlush(participation))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("사람 없이 참여를 저장하면 DB가 거부한다")
    void userIsRequired() {
        Participation participation = new Participation(groupOrder, null, JOINED_AT);

        assertThatThrownBy(() -> participationRepository.saveAndFlush(participation))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("참여 시각 없이 저장하면 DB가 거부한다")
    void joinedAtIsRequired() {
        Participation participation = new Participation(groupOrder, member, null);

        assertThatThrownBy(() -> participationRepository.saveAndFlush(participation))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 사람이 같은 방에 두 번 참여하면 DB가 거부한다")
    void sameUserCannotJoinSameGroupOrderTwice() {
        participationRepository.saveAndFlush(new Participation(groupOrder, member, JOINED_AT));

        assertThatThrownBy(() -> participationRepository.saveAndFlush(new Participation(groupOrder, member, JOINED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 사람이 다른 방에는 참여할 수 있다")
    void sameUserCanJoinOtherGroupOrder() {
        participationRepository.saveAndFlush(new Participation(groupOrder, member, JOINED_AT));
        participationRepository.saveAndFlush(new Participation(otherGroupOrder, member, JOINED_AT));

        assertThat(participationRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("이탈해서 행을 지운 사람은 같은 방에 다시 참여할 수 있다")
    void canRejoinAfterLeaving() {
        Participation first = participationRepository.saveAndFlush(new Participation(groupOrder, member, JOINED_AT));
        participationRepository.delete(first);
        participationRepository.flush();

        participationRepository.saveAndFlush(new Participation(groupOrder, member, JOINED_AT));

        assertThat(participationRepository.countByGroupOrderId(groupOrder.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("방의 인원수는 그 방의 참여 행만 센다")
    void countsOnlyThatGroupOrder() {
        participationRepository.save(new Participation(groupOrder, host, JOINED_AT));
        participationRepository.save(new Participation(groupOrder, member, JOINED_AT));
        participationRepository.save(new Participation(otherGroupOrder, host, JOINED_AT));
        entityManager.flush();

        assertThat(participationRepository.countByGroupOrderId(groupOrder.getId())).isEqualTo(2);
        assertThat(participationRepository.countByGroupOrderId(otherGroupOrder.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("여러 방의 인원수를 한 번에 방별로 센다")
    void countsManyGroupOrdersAtOnce() {
        participationRepository.save(new Participation(groupOrder, host, JOINED_AT));
        participationRepository.save(new Participation(groupOrder, member, JOINED_AT));
        participationRepository.save(new Participation(otherGroupOrder, host, JOINED_AT));
        entityManager.flush();

        List<GroupOrderStat> stats = participationRepository.countByGroupOrderIds(
                List.of(groupOrder.getId(), otherGroupOrder.getId()));

        assertThat(stats).containsExactlyInAnyOrder(
                new GroupOrderStat(groupOrder.getId(), 2),
                new GroupOrderStat(otherGroupOrder.getId(), 1));
    }

    @Test
    @DisplayName("넘기지 않은 방은 세지 않는다")
    void countsOnlyRequestedGroupOrders() {
        participationRepository.save(new Participation(groupOrder, host, JOINED_AT));
        participationRepository.save(new Participation(otherGroupOrder, host, JOINED_AT));
        entityManager.flush();

        List<GroupOrderStat> stats = participationRepository.countByGroupOrderIds(List.of(groupOrder.getId()));

        assertThat(stats).containsExactly(new GroupOrderStat(groupOrder.getId(), 1));
    }

    @Test
    @DisplayName("검수 명단은 그 방 참여자만, 들어온 순서로, 사용자까지 채워서 가져온다")
    void findsRosterWithUser() {
        Participation first = participationRepository.save(new Participation(groupOrder, host, JOINED_AT));
        Participation second = participationRepository.save(new Participation(groupOrder, member, JOINED_AT));
        participationRepository.save(new Participation(otherGroupOrder, host, JOINED_AT));
        entityManager.flush();
        entityManager.clear();

        List<Participation> roster = participationRepository.findRosterWithUser(groupOrder.getId());

        // joinedAt이 같으면 id 순서. 방장이 먼저 저장됐다
        assertThat(roster).extracting(Participation::getId).containsExactly(first.getId(), second.getId());
        // JOIN FETCH라 사용자가 프록시가 아니라 이미 읽힌 상태다. 닉네임을 꺼내도 쿼리가 안 나간다
        assertThat(roster).allSatisfy(p -> assertThat(Hibernate.isInitialized(p.getUser())).isTrue());
    }
}
