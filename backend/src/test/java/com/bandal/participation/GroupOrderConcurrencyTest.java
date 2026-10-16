package com.bandal.participation;

import com.bandal.TestcontainersConfiguration;
import com.bandal.grouporder.GroupOrder;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.grouporder.GroupOrderService;
import com.bandal.grouporder.GroupOrderStatus;
import com.bandal.grouporder.dto.CancelRequest;
import com.bandal.pickupspot.PickupSpot;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.settlement.Settlement;
import com.bandal.settlement.SettlementRepository;
import com.bandal.settlement.SettlementService;
import com.bandal.settlement.dto.DeliveryFeeRequest;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 같은 방을 바꾸는 요청들이 같은 순간에 들어온다. 참여, 나가기, 마감, 취소, 자동 마감, 배달비 입력 (Phase 3)
// 메뉴를 직접 넣으려고 OrderItem 생성자(패키지 안에서만 보임)를 써서 participation 패키지에 둔다
// 다른 테스트처럼 @Transactional로 감싸지 않는다. 스레드마다 자기 트랜잭션으로 커밋해야 진짜 동시 요청이 된다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GroupOrderConcurrencyTest {

    // 이만큼이 동시에 누른다. 둘만 누르면 우연히 한 줄로 서서 안 깨질 때가 있다
    static final int JOINERS = 10;
    static final String FULL = "IllegalStateException: 정원이 다 찼습니다";

    @Autowired
    ParticipationService participationService;

    @Autowired
    GroupOrderService groupOrderService;

    @Autowired
    GroupOrderRepository groupOrderRepository;

    @Autowired
    ParticipationRepository participationRepository;

    @Autowired
    OrderItemRepository orderItemRepository;

    @Autowired
    SettlementRepository settlementRepository;

    @Autowired
    SettlementService settlementService;

    @Autowired
    UniversityRepository universityRepository;

    @Autowired
    PickupSpotRepository pickupSpotRepository;

    @Autowired
    UserRepository userRepository;

    University university;
    PickupSpot pickupSpot;
    User host;
    List<User> joiners = new ArrayList<>();

    @BeforeEach
    void setUp() {
        settlementRepository.deleteAll();
        orderItemRepository.deleteAll();
        participationRepository.deleteAll();
        groupOrderRepository.deleteAll();
        userRepository.deleteAll();
        pickupSpotRepository.deleteAll();
        universityRepository.deleteAll();

        university = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
        pickupSpot = pickupSpotRepository.save(new PickupSpot(university, "제1기숙사 로비", null));
        host = verifiedUser("host");

        joiners.clear();
        for (int i = 0; i < JOINERS; i++) {
            joiners.add(verifiedUser("joiner" + i));
        }
    }

    User verifiedUser(String name) {
        User user = new User(university, name + "@hankuk.ac.kr", "hashed-password", name);
        ReflectionTestUtils.setField(user, "emailVerifiedAt", Instant.now());
        // 방장이 되려면 계좌가 있어야 해서 다 넣어 둔다
        ReflectionTestUtils.setField(user, "bankName", "한국은행");
        ReflectionTestUtils.setField(user, "accountNumber", "110-123-456789");
        ReflectionTestUtils.setField(user, "accountHolder", name);
        return userRepository.save(user);
    }

    // 정원 4명 방. 방장 말고 members명이 이미 들어와 있다
    GroupOrder roomWith(int members) {
        return roomWith(members, 4, new ArrayList<>());
    }

    // 정원 capacity명 방. 미리 넣은 참여 행을 memberParticipations에 담아 돌려준다(나가기 테스트용)
    GroupOrder roomWith(int members, int capacity, List<Participation> memberParticipations) {
        GroupOrder groupOrder = groupOrderRepository.save(new GroupOrder(
                host, pickupSpot, "○○마라탕", 15_000, Instant.now().plus(2, ChronoUnit.HOURS), capacity));
        participationRepository.save(new Participation(groupOrder, host, Instant.now()));
        for (int i = 0; i < members; i++) {
            // 방을 여러 개 만드는 테스트가 있어서 이메일과 닉네임이 겹치지 않게 방 id를 붙인다
            User member = verifiedUser("member" + groupOrder.getId() + "-" + i);
            memberParticipations.add(participationRepository.save(new Participation(groupOrder, member, Instant.now())));
        }
        // 위 사람들은 참여 서비스를 거치지 않고 넣었으니 방의 인원 칸도 직접 맞춘다. 안 맞추면 방은 1명이라고 믿는다
        ReflectionTestUtils.setField(groupOrder, "participantCount", 1 + members);
        return groupOrderRepository.save(groupOrder);
    }

    // 성공한 수와, 실패한 요청이 어떤 예외로 끝났는지. 막는 방법을 바꿀 때마다 실패 내역이 달라진다
    record Result(int succeeded, Map<String, Integer> failures, long rows, int participantCount) {
    }

    // 스레드 하나가 맡는 요청 하나
    interface Request {
        void send() throws Exception;
    }

    Result joinAllAtOnce(GroupOrder groupOrder) throws InterruptedException {
        List<Request> requests = new ArrayList<>();
        for (User joiner : joiners) {
            requests.add(() -> participationService.join(groupOrder.getId(), joiner.getId()));
        }
        return sendAllAtOnce(groupOrder, requests, "동시 참여");
    }

    Result sendAllAtOnce(GroupOrder groupOrder, List<Request> requests, String label) throws InterruptedException {
        int n = requests.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        // 출발선. 스레드가 다 준비될 때까지 붙잡아 뒀다가 한꺼번에 놓는다
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);

        AtomicInteger succeeded = new AtomicInteger();
        Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();

        for (Request request : requests) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    request.send();
                    succeeded.incrementAndGet();
                } catch (Exception e) {
                    String key = e.getClass().getSimpleName() + ": " + e.getMessage();
                    failures.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        // 락 방식마다 걸린 시간을 비교하려고 잰다
        long startedAt = System.nanoTime();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("30초 안에 끝나야 한다").isTrue();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        pool.shutdown();

        Map<String, Integer> counted = new TreeMap<>();
        failures.forEach((k, v) -> counted.put(k, v.get()));

        // 끝난 뒤 방의 인원 칸. 참여 행 수와 같아야 한다
        GroupOrder after = groupOrderRepository.findById(groupOrder.getId()).orElseThrow();
        System.out.println("[" + label + "] 성공 " + succeeded.get() + "명, 방 인원 "
                + participationRepository.countByGroupOrderId(groupOrder.getId()) + "명, 인원 칸 "
                + after.getParticipantCount()
                + ", 실패 " + counted + ", 걸린 시간 " + elapsedMs + "ms");
        return new Result(succeeded.get(), counted, participationRepository.countByGroupOrderId(groupOrder.getId()),
                after.getParticipantCount());
    }

    @Test
    @DisplayName("자리가 하나 남은 방에 10명이 동시에 참여하면 한 명만 들어가고 나머지는 정원 초과로 거절된다")
    void onlyOneJoinsTheLastSeat() throws InterruptedException {
        GroupOrder groupOrder = roomWith(2);

        Result result = joinAllAtOnce(groupOrder);

        assertThat(participationRepository.countByGroupOrderId(groupOrder.getId())).as("방 인원").isEqualTo(4);
        assertThat(result.succeeded()).as("참여에 성공한 사람 수").isEqualTo(1);
        // 서버 오류(버전 충돌)가 아니라 한 명씩 눌렀을 때와 같은 거절이어야 한다
        assertThat(result.failures()).as("실패 내역").isEqualTo(Map.of(FULL, 9));
    }

    @Test
    @DisplayName("자리가 셋 남은 방에 10명이 동시에 참여하면 세 명이 들어가고 나머지는 정원 초과로 거절된다")
    void threeJoinTheLastThreeSeats() throws InterruptedException {
        GroupOrder groupOrder = roomWith(0);

        Result result = joinAllAtOnce(groupOrder);

        assertThat(participationRepository.countByGroupOrderId(groupOrder.getId())).as("방 인원").isEqualTo(4);
        // 자리가 남았는데 순서에서 밀렸다고 거절되면 안 된다
        assertThat(result.succeeded()).as("참여에 성공한 사람 수").isEqualTo(3);
        assertThat(result.failures()).as("실패 내역").isEqualTo(Map.of(FULL, 7));
    }

    @Test
    @DisplayName("같은 사람이 같은 방에 참여를 10번 동시에 누르면 한 번만 들어가고 나머지는 이미 참여했다고 거절된다")
    void sameUserJoinsOnlyOnce() throws InterruptedException {
        // 한 번으로는 타이밍이 안 맞을 수 있어서 방 5개에서 반복한다
        for (int round = 0; round < 5; round++) {
            GroupOrder groupOrder = roomWith(0, 10, new ArrayList<>());
            User joiner = joiners.get(round);

            List<Request> requests = new ArrayList<>();
            for (int i = 0; i < JOINERS; i++) {
                requests.add(() -> participationService.join(groupOrder.getId(), joiner.getId()));
            }
            Result result = sendAllAtOnce(groupOrder, requests, "같은 사람 참여 " + (round + 1));

            assertThat(result.rows()).as("방 인원").isEqualTo(2);
            assertThat(result.participantCount()).as("방의 인원 칸").isEqualTo(2);
            assertThat(result.succeeded()).as("참여에 성공한 횟수").isEqualTo(1);
            // 유니크 제약 위반(500)이 아니라 한 번씩 눌렀을 때와 같은 거절(409)이어야 한다
            assertThat(result.failures()).as("실패 내역")
                    .isEqualTo(Map.of("IllegalStateException: 이미 참여한 방입니다", JOINERS - 1));
        }
    }

    @Test
    @DisplayName("참여와 나가기가 동시에 일어나도 전부 성공하고 방의 인원 칸이 실제 참여 행 수와 같다")
    void joinAndLeaveAtOnceKeepCountInSync() throws InterruptedException {
        // 한 번으로는 타이밍이 안 맞을 수 있어서 방 5개에서 반복한다
        List<Result> results = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            // 정원 10명 방에 방장 + 4명. 그 4명이 나가는 순간 5명이 들어온다. 자리는 넉넉하니 전부 성공해야 한다
            List<Participation> members = new ArrayList<>();
            GroupOrder groupOrder = roomWith(4, 10, members);

            List<Request> requests = new ArrayList<>();
            for (Participation member : members) {
                requests.add(() -> participationService.leave(member.getId(), member.getUser().getId()));
            }
            for (User joiner : joiners.subList(0, 5)) {
                requests.add(() -> participationService.join(groupOrder.getId(), joiner.getId()));
            }
            results.add(sendAllAtOnce(groupOrder, requests, "참여+나가기 " + (round + 1)));
        }

        for (Result result : results) {
            // 1 + 4 - 4 + 5 = 6명
            assertThat(result.rows()).as("참여 행 수").isEqualTo(6);
            assertThat(result.participantCount()).as("방의 인원 칸").isEqualTo(6);
            assertThat(result.failures()).as("실패 내역").isEmpty();
        }
    }

    @Test
    @DisplayName("방장의 마감과 참여가 동시에 일어나면 마감된 방의 사람은 방장이 마감할 때 센 사람과 같다")
    void closeAndJoinAtOnceKeepRosterFixed() throws InterruptedException {
        // 참여가 마감 처리의 한가운데에 끼어야 깨진다. 틈이 좁아서 방 10개에서 반복한다
        List<String> broken = new ArrayList<>();
        for (int round = 0; round < 10; round++) {
            // 정원 10명 방에 방장 + 2명. 최소주문금액(15,000원)은 이미 채워서 마감은 성공한다
            List<Participation> members = new ArrayList<>();
            GroupOrder groupOrder = roomWith(2, 10, members);
            orderItemRepository.save(new OrderItem(members.get(0), "마라탕", "보통맛", 15_000, 1));

            // 방장이 마감할 때 센 인원. 마감 응답에 실려 온다
            AtomicInteger countedByHost = new AtomicInteger(-1);
            List<Request> requests = new ArrayList<>();
            requests.add(() -> countedByHost.set(
                    (int) groupOrderService.close(groupOrder.getId(), host.getId()).participantCount()));
            for (User joiner : joiners.subList(0, 5)) {
                requests.add(() -> participationService.join(groupOrder.getId(), joiner.getId()));
            }
            Result result = sendAllAtOnce(groupOrder, requests, "마감+참여 " + (round + 1));

            GroupOrder after = groupOrderRepository.findById(groupOrder.getId()).orElseThrow();
            System.out.println("[마감+참여 " + (round + 1) + "] 방 상태 " + after.getStatus()
                    + ", 방장이 센 인원 " + countedByHost.get());
            // 마감 뒤에 들어온 사람이 있거나, 인원 칸이 실제 행과 다르면 깨진 방이다
            if (countedByHost.get() != result.rows() || result.participantCount() != result.rows()) {
                broken.add("방 " + (round + 1) + ": 방장이 센 인원 " + countedByHost.get()
                        + ", 참여 행 " + result.rows() + ", 인원 칸 " + result.participantCount());
            }
        }

        assertThat(broken).as("깨진 방").isEmpty();
    }

    @Test
    @DisplayName("방장이 배달비 입력을 두 번 동시에 눌러도 정산표는 한 벌만 생기고 늦은 쪽은 거절된다")
    void doubleStartSettlementMakesOneTable() throws InterruptedException {
        List<String> broken = new ArrayList<>();
        for (int round = 0; round < 10; round++) {
            // 방장 + 2명, 메뉴 15,000원으로 마감까지 해 둔 방. 정산표는 3줄이어야 한다
            List<Participation> members = new ArrayList<>();
            GroupOrder groupOrder = roomWith(2, 10, members);
            orderItemRepository.save(new OrderItem(members.get(0), "마라탕", "보통맛", 15_000, 1));
            groupOrderService.close(groupOrder.getId(), host.getId());

            // 응답이 늦어 버튼을 두 번 누른 장면
            List<Request> requests = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                requests.add(() -> settlementService.start(groupOrder.getId(), host.getId(), new DeliveryFeeRequest(3_000L)));
            }
            Result result = sendAllAtOnce(groupOrder, requests, "배달비 두 번 " + (round + 1));

            int lines = settlementRepository.findByGroupOrderId(groupOrder.getId()).size();
            System.out.println("[배달비 두 번 " + (round + 1) + "] 정산표 " + lines + "줄");
            if (lines != 3 || result.succeeded() != 1) {
                broken.add("방 " + (round + 1) + ": 성공 " + result.succeeded() + ", 정산표 " + lines + "줄, 실패 " + result.failures());
            }
        }

        assertThat(broken).as("깨진 방").isEmpty();
    }

    @Test
    @DisplayName("방장의 배달비 수정과 참여자의 보냈어요가 동시에 일어나도 보냈어요 기록이 사라지지 않는다")
    void changeFeeAndMarkPaidAtOnceKeepPaymentRecord() throws InterruptedException {
        List<String> broken = new ArrayList<>();
        for (int round = 0; round < 10; round++) {
            // 방장 + 2명, 정산중인 방. 참여자 둘이 보냈어요를 누르는 순간 방장이 배달비를 고친다
            List<Participation> members = new ArrayList<>();
            GroupOrder groupOrder = roomWith(2, 10, members);
            orderItemRepository.save(new OrderItem(members.get(0), "마라탕", "보통맛", 15_000, 1));
            groupOrderService.close(groupOrder.getId(), host.getId());
            settlementService.start(groupOrder.getId(), host.getId(), new DeliveryFeeRequest(3_000L));

            AtomicInteger paid = new AtomicInteger();
            AtomicInteger feeChanged = new AtomicInteger();
            List<Request> requests = new ArrayList<>();
            requests.add(() -> {
                settlementService.changeDeliveryFee(groupOrder.getId(), host.getId(), new DeliveryFeeRequest(4_000L));
                feeChanged.incrementAndGet();
            });
            for (Participation member : members) {
                Long userId = member.getUser().getId();
                Long lineId = settlementRepository.findByGroupOrderIdAndUserId(groupOrder.getId(), userId).orElseThrow().getId();
                requests.add(() -> {
                    settlementService.markPaid(lineId, userId);
                    paid.incrementAndGet();
                });
            }
            Result result = sendAllAtOnce(groupOrder, requests, "배달비 수정+보냈어요 " + (round + 1));

            long records = settlementRepository.findByGroupOrderId(groupOrder.getId()).stream()
                    .filter(s -> s.hasPaymentRecord()).count();
            System.out.println("[배달비 수정+보냈어요 " + (round + 1) + "] 보냈어요 성공 " + paid.get()
                    + ", 배달비 수정 성공 " + feeChanged.get() + ", 남은 입금 기록 " + records);
            // 성공했다고 응답한 보냈어요는 기록이 남아 있어야 한다
            if (records != paid.get()) {
                broken.add("방 " + (round + 1) + ": 보냈어요 성공 " + paid.get() + ", 남은 입금 기록 " + records
                        + ", 배달비 수정 성공 " + feeChanged.get() + ", 실패 " + result.failures());
            }
        }

        assertThat(broken).as("깨진 방").isEmpty();
    }

    @Test
    @DisplayName("참여자의 보냈어요와 방장의 받았어요가 같은 줄에 동시에 일어나도 두 기록이 모두 남는다")
    void markPaidAndConfirmAtOnceKeepBoth() throws InterruptedException {
        List<String> broken = new ArrayList<>();
        for (int round = 0; round < 10; round++) {
            List<Participation> members = new ArrayList<>();
            GroupOrder groupOrder = roomWith(2, 10, members);
            orderItemRepository.save(new OrderItem(members.get(0), "마라탕", "보통맛", 15_000, 1));
            groupOrderService.close(groupOrder.getId(), host.getId());
            settlementService.start(groupOrder.getId(), host.getId(), new DeliveryFeeRequest(3_000L));

            // 방장은 통장을 보고 바로 확인을 누르고, 참여자는 같은 순간 보냈어요를 누른다
            Long userId = members.get(0).getUser().getId();
            Long lineId = settlementRepository.findByGroupOrderIdAndUserId(groupOrder.getId(), userId).orElseThrow().getId();
            List<Request> requests = List.of(
                    () -> settlementService.markPaid(lineId, userId),
                    () -> settlementService.confirm(lineId, host.getId()));
            Result result = sendAllAtOnce(groupOrder, requests, "보냈어요+받았어요 " + (round + 1));

            Settlement line = settlementRepository.findById(lineId).orElseThrow();
            System.out.println("[보냈어요+받았어요 " + (round + 1) + "] 보낸 시각 " + line.getMarkedPaidAt()
                    + ", 확인 시각 " + line.getConfirmedPaidAt());
            // 둘 다 성공했다면 두 칸이 모두 차 있어야 한다
            if (result.succeeded() == 2 && (line.getMarkedPaidAt() == null || line.getConfirmedPaidAt() == null)) {
                broken.add("방 " + (round + 1) + ": 보낸 시각 " + line.getMarkedPaidAt() + ", 확인 시각 " + line.getConfirmedPaidAt());
            }
        }

        assertThat(broken).as("깨진 방").isEmpty();
    }

    @Test
    @DisplayName("마감 시각에 스케줄러의 자동 마감과 참여가 동시에 일어나도 방의 인원 칸이 실제 참여 행 수와 같다")
    void expireAndJoinAtOnceKeepCountInSync() throws InterruptedException {
        List<String> broken = new ArrayList<>();
        for (int round = 0; round < 10; round++) {
            // 정원 10명 방에 방장 + 2명, 메뉴 15,000원. 마감 시각이 되면 자동 마감 조건을 채운다
            List<Participation> members = new ArrayList<>();
            GroupOrder groupOrder = roomWith(2, 10, members);
            orderItemRepository.save(new OrderItem(members.get(0), "마라탕", "보통맛", 15_000, 1));

            // 참여는 지금 시각(마감 2시간 전)으로, 스케줄러는 마감 시각 직후로 부른다.
            // 마감 직전에 누른 참여가 아직 처리 중일 때 스케줄러가 도는 장면이다
            Instant schedulerNow = groupOrder.getDeadlineAt().plusSeconds(1);
            List<Request> requests = new ArrayList<>();
            requests.add(() -> groupOrderService.expire(groupOrder.getId(), schedulerNow));
            for (User joiner : joiners.subList(0, 5)) {
                requests.add(() -> participationService.join(groupOrder.getId(), joiner.getId()));
            }
            Result result = sendAllAtOnce(groupOrder, requests, "자동마감+참여 " + (round + 1));

            GroupOrder after = groupOrderRepository.findById(groupOrder.getId()).orElseThrow();
            System.out.println("[자동마감+참여 " + (round + 1) + "] 방 상태 " + after.getStatus());
            if (after.getStatus() != GroupOrderStatus.CLOSED || result.participantCount() != result.rows()) {
                broken.add("방 " + (round + 1) + ": 상태 " + after.getStatus()
                        + ", 참여 행 " + result.rows() + ", 인원 칸 " + result.participantCount());
            }
        }

        assertThat(broken).as("깨진 방").isEmpty();
    }

    @Test
    @DisplayName("방장의 취소와 참여가 동시에 일어나도 취소는 성공하고 방의 인원 칸이 실제 참여 행 수와 같다")
    void cancelAndJoinAtOnceKeepCountInSync() throws InterruptedException {
        List<String> broken = new ArrayList<>();
        for (int round = 0; round < 10; round++) {
            // 정원 10명 방에 방장 + 2명. 모집중 취소라 사유는 비워도 된다
            GroupOrder groupOrder = roomWith(2, 10, new ArrayList<>());

            List<Request> requests = new ArrayList<>();
            requests.add(() -> groupOrderService.cancel(groupOrder.getId(), host.getId(), new CancelRequest(null)));
            for (User joiner : joiners.subList(0, 5)) {
                requests.add(() -> participationService.join(groupOrder.getId(), joiner.getId()));
            }
            Result result = sendAllAtOnce(groupOrder, requests, "취소+참여 " + (round + 1));

            GroupOrder after = groupOrderRepository.findById(groupOrder.getId()).orElseThrow();
            System.out.println("[취소+참여 " + (round + 1) + "] 방 상태 " + after.getStatus());
            // 취소보다 먼저 잠근 참여는 들어가고, 뒤에 온 참여는 "모집이 끝난 방입니다"로 거절돼야 한다
            if (after.getStatus() != GroupOrderStatus.CANCELED || result.participantCount() != result.rows()) {
                broken.add("방 " + (round + 1) + ": 상태 " + after.getStatus()
                        + ", 참여 행 " + result.rows() + ", 인원 칸 " + result.participantCount());
            }
        }

        assertThat(broken).as("깨진 방").isEmpty();
    }

}