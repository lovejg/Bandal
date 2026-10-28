package com.bandal.outbox;

import com.bandal.TestcontainersConfiguration;
import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.grouporder.GroupOrderService;
import com.bandal.grouporder.GroupOrderStatus;
import com.bandal.grouporder.dto.CancelRequest;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.Participation;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpot;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.settlement.Settlement;
import com.bandal.settlement.SettlementRepository;
import com.bandal.settlement.SettlementService;
import com.bandal.settlement.dto.DeliveryFeeRequest;
import com.bandal.settlement.dto.OrderRequest;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 방 상태를 바꾼 트랜잭션이 커밋되면 outbox에 방 상태 줄이 하나 생기는가. 내용은 그 순간의 사실인가
// 서비스를 실제로 불러 커밋까지 간 뒤 DB에서 줄을 읽는다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GroupOrderStatusOutboxTest {

    static final long HOST_MENU = 8_000;
    static final long MEMBER_MENU = 9_000;
    static final long DELIVERY_FEE = 3_000;

    @Autowired
    GroupOrderService groupOrderService;

    @Autowired
    SettlementService settlementService;

    @Autowired
    OutboxMessageRepository outboxMessageRepository;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    UniversityRepository universityRepository;

    @Autowired
    PickupSpotRepository pickupSpotRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    GroupOrderRepository groupOrderRepository;

    @Autowired
    ParticipationRepository participationRepository;

    @Autowired
    OrderItemRepository orderItemRepository;

    @Autowired
    SettlementRepository settlementRepository;

    PickupSpot pickupSpot;
    User host;
    User member;
    Instant now;

    @BeforeEach
    void setUp() {
        outboxMessageRepository.deleteAll();
        settlementRepository.deleteAll();
        orderItemRepository.deleteAll();
        participationRepository.deleteAll();
        groupOrderRepository.deleteAll();
        userRepository.deleteAll();
        pickupSpotRepository.deleteAll();
        universityRepository.deleteAll();

        University university = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        pickupSpot = pickupSpotRepository.save(new PickupSpot(university, "제1기숙사 로비", null));
        host = saveUser(university, "kim@hankuk.ac.kr", "배고파");
        member = saveUser(university, "lee@hankuk.ac.kr", "마라탕러버");
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    User saveUser(University university, String email, String nickname) {
        User user = new User(university, email, "hashed-password", nickname);
        ReflectionTestUtils.setField(user, "emailVerifiedAt", Instant.now());
        ReflectionTestUtils.setField(user, "bankName", "한국은행");
        ReflectionTestUtils.setField(user, "accountNumber", "110-123-456789");
        ReflectionTestUtils.setField(user, "accountHolder", "김민수");
        return userRepository.save(user);
    }

    // 방장과 참여자 한 명이 메뉴를 담아 최소주문금액을 넘긴 모집중 방
    GroupOrder recruitingRoom() {
        GroupOrder room = groupOrderRepository.save(new GroupOrder(
                host, pickupSpot, "○○마라탕", 15_000, now.plus(2, ChronoUnit.HOURS), 4));
        Participation hostPart = participationRepository.save(new Participation(room, host, now));
        orderItemRepository.save(hostPart.addItem(host.getId(), "마라탕", null, HOST_MENU, 1, now));
        Participation memberPart = participationRepository.save(new Participation(room, member, now));
        orderItemRepository.save(memberPart.addItem(member.getId(), "마라탕 소", null, MEMBER_MENU, 1, now));
        return room;
    }

    // 서비스로 마감한 방. 이때 생긴 outbox 줄은 지운다
    GroupOrder closedRoom() {
        GroupOrder room = recruitingRoom();
        groupOrderService.close(room.getId(), host.getId());
        outboxMessageRepository.deleteAll();
        return room;
    }

    // 서비스로 정산을 시작한 방. 이때 생긴 outbox 줄은 지운다
    GroupOrder settlingRoom() {
        GroupOrder room = closedRoom();
        settlementService.start(room.getId(), host.getId(), new DeliveryFeeRequest(DELIVERY_FEE));
        outboxMessageRepository.deleteAll();
        return room;
    }

    // outbox에 방 상태 줄이 정확히 하나 있고, 이 방의 것이다. 내용을 JSON으로 돌려준다
    JsonNode onlyRow(GroupOrder room) {
        List<OutboxMessage> rows = outboxMessageRepository.findAll();
        assertThat(rows).hasSize(1);
        OutboxMessage row = rows.get(0);
        assertThat(row.getType()).isEqualTo(OutboxType.GROUP_ORDER_STATUS_CHANGED);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getGroupOrderId()).isEqualTo(room.getId());
        assertThat(row.getUserId()).isNull();
        return objectMapper.readTree(row.getPayload());
    }

    // 참여자 userId → 금액(없으면 null)
    Map<Long, JsonNode> amountsByUser(JsonNode payload) {
        return StreamSupport.stream(payload.get("participants").spliterator(), false)
                .collect(Collectors.toMap(p -> p.get("userId").asLong(), p -> p.get("totalAmount")));
    }

    @Test
    @DisplayName("방장 마감: 방, 거점, 가게, 방장, 이전/다음 상태, 참여자 둘이 담긴다. 정산 전이라 금액과 배달비는 없다")
    void close() {
        GroupOrder room = recruitingRoom();

        groupOrderService.close(room.getId(), host.getId());

        JsonNode payload = onlyRow(room);
        assertThat(payload.get("groupOrderId").asLong()).isEqualTo(room.getId());
        assertThat(payload.get("pickupSpotId").asLong()).isEqualTo(pickupSpot.getId());
        assertThat(payload.get("storeName").asString()).isEqualTo("○○마라탕");
        assertThat(payload.get("hostId").asLong()).isEqualTo(host.getId());
        assertThat(payload.get("from").asString()).isEqualTo("RECRUITING");
        assertThat(payload.get("to").asString()).isEqualTo("CLOSED");
        assertThat(payload.get("changedBy").asString()).isEqualTo("HOST");
        assertThat(payload.get("deliveryFee").isNull()).isTrue();
        assertThat(payload.get("cancelType").isNull()).isTrue();
        assertThat(payload.get("occurredAt").isNull()).isFalse();

        Map<Long, JsonNode> amounts = amountsByUser(payload);
        assertThat(amounts.keySet()).containsExactlyInAnyOrder(host.getId(), member.getId());
        assertThat(amounts.values()).allMatch(JsonNode::isNull);
    }

    @Test
    @DisplayName("정산 시작: 서비스가 뒤에 만든 정산표의 사람별 금액과 배달비가 담긴다")
    void startSettlement() {
        GroupOrder room = closedRoom();

        settlementService.start(room.getId(), host.getId(), new DeliveryFeeRequest(DELIVERY_FEE));

        JsonNode payload = onlyRow(room);
        assertThat(payload.get("from").asString()).isEqualTo("CLOSED");
        assertThat(payload.get("to").asString()).isEqualTo("SETTLING");
        assertThat(payload.get("deliveryFee").asLong()).isEqualTo(DELIVERY_FEE);

        // 금액은 정산표와 같아야 한다(나누어떨어지지 않는 몫까지 정산 계산기가 정한 값)
        Map<Long, Long> expected = settlementRepository.findByGroupOrderId(room.getId()).stream()
                .collect(Collectors.toMap(s -> s.getUser().getId(), Settlement::getTotalAmount));
        Map<Long, JsonNode> amounts = amountsByUser(payload);
        assertThat(amounts).hasSize(2);
        amounts.forEach((userId, amount) -> assertThat(amount.asLong()).isEqualTo(expected.get(userId)));
    }

    @Test
    @DisplayName("방장 취소: 취소 종류와 방장이 적은 사유가 담긴다")
    void cancel() {
        GroupOrder room = closedRoom();

        groupOrderService.cancel(room.getId(), host.getId(), new CancelRequest("가게가 문을 닫았어요"));

        JsonNode payload = onlyRow(room);
        assertThat(payload.get("from").asString()).isEqualTo("CLOSED");
        assertThat(payload.get("to").asString()).isEqualTo("CANCELED");
        assertThat(payload.get("cancelType").asString()).isEqualTo("HOST_WHILE_CLOSED");
        assertThat(payload.get("cancelReason").asString()).isEqualTo("가게가 문을 닫았어요");
    }

    @Test
    @DisplayName("스케줄러가 멈춘 방을 넘겨도 줄이 생긴다")
    void expire() {
        GroupOrder room = closedRoom();

        groupOrderService.expire(room.getId(), Instant.now().plus(GroupOrder.CLOSED_EXPIRY).plusSeconds(60));

        JsonNode payload = onlyRow(room);
        assertThat(payload.get("to").asString()).isEqualTo("CANCELED");
        assertThat(payload.get("cancelType").asString()).isEqualTo("CLOSED_TIMEOUT");
        // 스케줄러가 넘겼다. 방장에게도 알릴 수 있게 SYSTEM으로 나간다
        assertThat(payload.get("changedBy").asString()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("상태는 바꿨지만 같은 트랜잭션에서 거절돼 롤백되면 줄도 없다")
    void rolledBack() {
        // 참여자 입금이 확인되지 않았다. 엔티티는 주문완료로 바꾸고 save까지 했지만, 서비스가 뒤에서 거절해 트랜잭션 전체가 롤백된다
        GroupOrder room = settlingRoom();

        assertThatThrownBy(() -> settlementService.order(room.getId(), host.getId(), new OrderRequest(null, Instant.now().plusSeconds(2400))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(outboxMessageRepository.findAll()).isEmpty();
        assertThat(groupOrderRepository.findById(room.getId()).orElseThrow().getStatus())
                .isEqualTo(GroupOrderStatus.SETTLING);
    }

    @Test
    @DisplayName("배달비 수정: 상태는 그대로이고, 종류와 바뀐 배달비, 새로 만든 정산표의 금액이 담긴다")
    void changeDeliveryFee() {
        GroupOrder room = settlingRoom();

        settlementService.changeDeliveryFee(room.getId(), host.getId(), new DeliveryFeeRequest(4_000L));

        JsonNode payload = onlyRow(room);
        assertThat(payload.get("kind").asString()).isEqualTo("DELIVERY_FEE_CHANGED");
        assertThat(payload.get("from").asString()).isEqualTo("SETTLING");
        assertThat(payload.get("to").asString()).isEqualTo("SETTLING");
        assertThat(payload.get("changedBy").asString()).isEqualTo("HOST");
        assertThat(payload.get("deliveryFee").asLong()).isEqualTo(4_000L);

        // 금액은 고친 배달비로 다시 만든 정산표와 같아야 한다(지운 옛 정산표의 금액이 아니라)
        Map<Long, Long> expected = settlementRepository.findByGroupOrderId(room.getId()).stream()
                .collect(Collectors.toMap(s -> s.getUser().getId(), Settlement::getTotalAmount));
        Map<Long, JsonNode> amounts = amountsByUser(payload);
        assertThat(amounts).hasSize(2);
        amounts.forEach((userId, amount) -> assertThat(amount.asLong()).isEqualTo(expected.get(userId)));
    }

    @Test
    @DisplayName("상태 변경 줄의 종류는 상태 변경이다")
    void statusChangeKind() {
        GroupOrder room = recruitingRoom();

        groupOrderService.close(room.getId(), host.getId());

        assertThat(onlyRow(room).get("kind").asString()).isEqualTo("STATUS_CHANGED");
    }

    @Test
    @DisplayName("같은 배달비로 고치면 줄을 만들지 않는다")
    void sameDeliveryFee() {
        GroupOrder room = settlingRoom();

        settlementService.changeDeliveryFee(room.getId(), host.getId(), new DeliveryFeeRequest(DELIVERY_FEE));

        assertThat(outboxMessageRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("배달비 수정이 입금 기록 때문에 거절되면, save로 이미 내보낸 사건도 롤백돼 줄이 없다")
    void deliveryFeeRejectedAfterSave() {
        // 서비스는 save를 먼저 하고 입금 기록을 뒤에서 본다. 사건은 커밋 직전에 outbox로 옮겨 적으니 롤백되면 같이 사라져야 한다
        GroupOrder room = settlingRoom();
        Settlement memberLine = settlementRepository.findByGroupOrderId(room.getId()).stream()
                .filter(s -> s.getUser().getId().equals(member.getId()))
                .findFirst().orElseThrow();
        settlementService.markPaid(memberLine.getId(), member.getId());

        assertThatThrownBy(() -> settlementService.changeDeliveryFee(room.getId(), host.getId(), new DeliveryFeeRequest(4_000L)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(outboxMessageRepository.findAll()).isEmpty();
        assertThat(groupOrderRepository.findById(room.getId()).orElseThrow().getDeliveryFee()).isEqualTo(DELIVERY_FEE);
    }
}
