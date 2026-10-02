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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class OrderItemRepositoryTest {

    static final Instant DEADLINE = Instant.parse("2026-09-22T10:30:00Z");
    static final Instant JOINED_AT = Instant.parse("2026-09-22T09:00:00Z");

    @Autowired
    OrderItemRepository orderItemRepository;

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

    Long hostId;
    Long memberId;
    GroupOrder groupOrder;
    GroupOrder emptyGroupOrder;
    Participation hostParticipation;
    Participation memberParticipation;
    Participation otherRoomParticipation;

    // 방 두 개를 깐다. groupOrder에는 방장과 참여자 한 명, emptyGroupOrder에는 방장만
    @BeforeEach
    void setUp() {
        University university = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        PickupSpot pickupSpot = pickupSpotRepository.save(new PickupSpot(university, "제1기숙사 로비", null));
        User host = userRepository.save(new User(university, "kim@hankuk.ac.kr", "hashed-password", "배고파"));
        // 계좌가 없으면 방을 만들 수 없다 (ADR-031)
        host.registerAccount("한국은행", "110-123-456789", "김민수");
        User member = userRepository.save(new User(university, "lee@hankuk.ac.kr", "hashed-password", "마라탕러버"));
        hostId = host.getId();
        memberId = member.getId();

        groupOrder = groupOrderRepository.save(new GroupOrder(host, pickupSpot, "○○마라탕", 15_000, DEADLINE, 4));
        emptyGroupOrder = groupOrderRepository.save(new GroupOrder(host, pickupSpot, "△△치킨", 20_000, DEADLINE, 3));

        hostParticipation = participationRepository.save(new Participation(groupOrder, host, JOINED_AT));
        memberParticipation = participationRepository.save(new Participation(groupOrder, member, JOINED_AT));
        otherRoomParticipation = participationRepository.save(new Participation(emptyGroupOrder, host, JOINED_AT));
    }

    @Test
    @DisplayName("메뉴를 저장하고 다시 꺼내면 입력한 값이 그대로다")
    void saveAndFindById() {
        OrderItem item = memberParticipation.addItem(memberId, "마라탕", "2단계, 꿔바로우 추가", 12_000, 2, JOINED_AT);
        OrderItem saved = orderItemRepository.save(item);
        entityManager.flush();
        entityManager.clear();

        OrderItem found = orderItemRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getParticipation().getId()).isEqualTo(memberParticipation.getId());
        assertThat(found.getMenuName()).isEqualTo("마라탕");
        assertThat(found.getOptions()).isEqualTo("2단계, 꿔바로우 추가");
        assertThat(found.getUnitPrice()).isEqualTo(12_000);
        assertThat(found.getQuantity()).isEqualTo(2);
        assertThat(found.getAmount()).isEqualTo(24_000);
        assertThat(found.isEditedByHost()).isFalse();
    }

    @Test
    @DisplayName("메뉴를 조회해도 참여는 바로 가져오지 않는다")
    void participationIsLazy() {
        OrderItem saved = orderItemRepository.save(memberParticipation.addItem(memberId, "마라탕", null, 9_000, 1, JOINED_AT));
        entityManager.flush();
        entityManager.clear();

        OrderItem found = orderItemRepository.findById(saved.getId()).orElseThrow();

        assertThat(Hibernate.isInitialized(found.getParticipation())).isFalse();
    }

    @Test
    @DisplayName("한 참여자가 담은 메뉴만 모아서 꺼낸다")
    void findsItemsOfOneParticipation() {
        orderItemRepository.save(memberParticipation.addItem(memberId, "마라탕", "2단계", 9_000, 1, JOINED_AT));
        orderItemRepository.save(memberParticipation.addItem(memberId, "공기밥", null, 1_000, 1, JOINED_AT));
        orderItemRepository.save(hostParticipation.addItem(hostId, "꿔바로우", "소", 12_000, 1, JOINED_AT));
        entityManager.flush();

        assertThat(orderItemRepository.findByParticipationId(memberParticipation.getId())).hasSize(2);
        assertThat(orderItemRepository.findByParticipationId(hostParticipation.getId())).hasSize(1);
    }

    @Test
    @DisplayName("방의 메뉴 합계는 참여자 전원의 줄 금액을 더한 값이다")
    void sumsAmountOfGroupOrder() {
        orderItemRepository.save(hostParticipation.addItem(hostId, "꿔바로우", "소", 12_000, 1, JOINED_AT));
        orderItemRepository.save(memberParticipation.addItem(memberId, "마라탕", "2단계", 9_000, 2, JOINED_AT));
        entityManager.flush();

        // 12,000 * 1 + 9,000 * 2
        assertThat(orderItemRepository.sumAmountByGroupOrderId(groupOrder.getId())).isEqualTo(30_000);
    }

    @Test
    @DisplayName("합계는 그 방의 메뉴만 센다")
    void sumsOnlyThatGroupOrder() {
        orderItemRepository.save(memberParticipation.addItem(memberId, "마라탕", null, 9_000, 1, JOINED_AT));
        orderItemRepository.save(otherRoomParticipation.addItem(hostId, "치킨", "양념", 20_000, 1, JOINED_AT));
        entityManager.flush();

        assertThat(orderItemRepository.sumAmountByGroupOrderId(groupOrder.getId())).isEqualTo(9_000);
        assertThat(orderItemRepository.sumAmountByGroupOrderId(emptyGroupOrder.getId())).isEqualTo(20_000);
    }

    @Test
    @DisplayName("아무도 메뉴를 담지 않은 방의 합계는 0이다")
    void sumIsZeroWhenNoItems() {
        assertThat(orderItemRepository.sumAmountByGroupOrderId(emptyGroupOrder.getId())).isEqualTo(0);
    }

    @Test
    @DisplayName("메뉴를 빼면 행이 사라지고 참여는 남는다")
    void deletedItemIsGone() {
        OrderItem keep = orderItemRepository.save(memberParticipation.addItem(memberId, "마라탕", null, 9_000, 1, JOINED_AT));
        OrderItem remove = orderItemRepository.save(memberParticipation.addItem(memberId, "공기밥", null, 1_000, 1, JOINED_AT));
        entityManager.flush();

        memberParticipation.removeItem(memberId, remove, JOINED_AT);
        orderItemRepository.delete(remove);
        entityManager.flush();
        entityManager.clear();

        assertThat(orderItemRepository.findById(remove.getId())).isEmpty();
        assertThat(orderItemRepository.findById(keep.getId())).isPresent();
        assertThat(participationRepository.findById(memberParticipation.getId())).isPresent();
        assertThat(orderItemRepository.sumAmountByGroupOrderId(groupOrder.getId())).isEqualTo(9_000);
    }

    @Test
    @DisplayName("여러 방의 메뉴 합계를 한 번에 방별로 더한다")
    void sumsManyGroupOrdersAtOnce() {
        orderItemRepository.save(hostParticipation.addItem(hostId, "꿔바로우", "소", 12_000, 1, JOINED_AT));
        orderItemRepository.save(memberParticipation.addItem(memberId, "마라탕", "2단계", 9_000, 2, JOINED_AT));
        orderItemRepository.save(otherRoomParticipation.addItem(hostId, "치킨", "양념", 20_000, 1, JOINED_AT));
        entityManager.flush();

        List<GroupOrderStat> stats = orderItemRepository.sumAmountByGroupOrderIds(
                List.of(groupOrder.getId(), emptyGroupOrder.getId()));

        // groupOrder: 12,000 * 1 + 9,000 * 2 / emptyGroupOrder: 20,000 * 1
        assertThat(stats).containsExactlyInAnyOrder(
                new GroupOrderStat(groupOrder.getId(), 30_000),
                new GroupOrderStat(emptyGroupOrder.getId(), 20_000));
    }

    @Test
    @DisplayName("메뉴가 없는 방은 결과에 줄이 아예 없다")
    void roomWithoutItemsIsMissing() {
        orderItemRepository.save(memberParticipation.addItem(memberId, "마라탕", null, 9_000, 1, JOINED_AT));
        entityManager.flush();

        List<GroupOrderStat> stats = orderItemRepository.sumAmountByGroupOrderIds(
                List.of(groupOrder.getId(), emptyGroupOrder.getId()));

        // 0으로 나오는 게 아니라 빠진다. 서비스가 0으로 채워야 한다
        assertThat(stats).containsExactly(new GroupOrderStat(groupOrder.getId(), 9_000));
    }

    @Test
    @DisplayName("한 참여자의 메뉴를 한 번에 지우고, 지운 개수를 돌려준다")
    void deletesAllItemsOfOneParticipation() {
        orderItemRepository.save(memberParticipation.addItem(memberId, "마라탕", "2단계", 9_000, 1, JOINED_AT));
        orderItemRepository.save(memberParticipation.addItem(memberId, "공기밥", null, 1_000, 1, JOINED_AT));
        entityManager.flush();

        int deleted = orderItemRepository.deleteByParticipationId(memberParticipation.getId());

        assertThat(deleted).isEqualTo(2);
        assertThat(orderItemRepository.findByParticipationId(memberParticipation.getId())).isEmpty();
    }

    @Test
    @DisplayName("같은 방의 다른 참여자 메뉴와 다른 방의 메뉴는 지우지 않는다")
    void deletesOnlyThatParticipation() {
        orderItemRepository.save(memberParticipation.addItem(memberId, "마라탕", null, 9_000, 1, JOINED_AT));
        orderItemRepository.save(hostParticipation.addItem(hostId, "꿔바로우", "소", 12_000, 1, JOINED_AT));
        orderItemRepository.save(otherRoomParticipation.addItem(hostId, "치킨", "양념", 20_000, 1, JOINED_AT));
        entityManager.flush();

        orderItemRepository.deleteByParticipationId(memberParticipation.getId());

        assertThat(orderItemRepository.findByParticipationId(hostParticipation.getId())).hasSize(1);
        assertThat(orderItemRepository.findByParticipationId(otherRoomParticipation.getId())).hasSize(1);
    }

    @Test
    @DisplayName("메뉴가 없는 참여자면 아무것도 지우지 않고 0을 돌려준다")
    void deletesNothingWhenNoItems() {
        // 메뉴 없이 나가는 사람도 같은 길을 지난다. 에러 없이 지나가야 한다
        assertThat(orderItemRepository.deleteByParticipationId(memberParticipation.getId())).isZero();
    }

    @Test
    @DisplayName("메뉴를 먼저 지우면 참여 행도 지울 수 있다")
    void participationCanBeDeletedAfterItems() {
        // 순서가 반대면 외래 키에 걸린다 (JOURNAL 2026-10-01)
        orderItemRepository.save(memberParticipation.addItem(memberId, "마라탕", null, 9_000, 1, JOINED_AT));
        entityManager.flush();

        orderItemRepository.deleteByParticipationId(memberParticipation.getId());
        participationRepository.delete(memberParticipation);
        entityManager.flush();

        assertThat(participationRepository.findById(memberParticipation.getId())).isEmpty();
    }
}
