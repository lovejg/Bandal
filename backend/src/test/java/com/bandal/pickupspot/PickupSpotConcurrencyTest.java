package com.bandal.pickupspot;

import com.bandal.TestcontainersConfiguration;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.dto.CreatePickupSpotRequest;
import com.bandal.settlement.SettlementRepository;
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

// 여러 사람이 같은 이름의 거점을 같은 순간에 만든다 (Phase 3)
// 다른 테스트처럼 @Transactional로 감싸지 않는다. 스레드마다 자기 트랜잭션으로 커밋해야 진짜 동시 요청이 된다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PickupSpotConcurrencyTest {

    static final int CREATORS = 10;

    @Autowired
    PickupSpotService pickupSpotService;

    @Autowired
    PickupSpotRepository pickupSpotRepository;

    @Autowired
    SettlementRepository settlementRepository;

    @Autowired
    OrderItemRepository orderItemRepository;

    @Autowired
    ParticipationRepository participationRepository;

    @Autowired
    GroupOrderRepository groupOrderRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    UniversityRepository universityRepository;

    University university;
    List<User> creators = new ArrayList<>();

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
        creators.clear();
        for (int i = 0; i < CREATORS; i++) {
            creators.add(userRepository.save(
                    new User(university, "creator" + i + "@hankuk.ac.kr", "hashed-password", "creator" + i)));
        }
    }

    @Test
    @DisplayName("10명이 같은 이름의 거점을 동시에 만들면 하나만 만들어지고 나머지는 이미 있다고 거절된다")
    void onlyOneSpotWithTheSameName() throws InterruptedException {
        // 한 번으로는 타이밍이 안 맞을 수 있어서 이름을 바꿔 가며 5번 반복한다
        for (int round = 0; round < 5; round++) {
            String name = "공대 " + round + "호관 앞";

            ExecutorService pool = Executors.newFixedThreadPool(CREATORS);
            // 출발선. 스레드가 다 준비될 때까지 붙잡아 뒀다가 한꺼번에 놓는다
            CountDownLatch ready = new CountDownLatch(CREATORS);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(CREATORS);
            AtomicInteger succeeded = new AtomicInteger();
            Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();

            for (User creator : creators) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        pickupSpotService.create(creator.getId(), new CreatePickupSpotRequest(name, null));
                        succeeded.incrementAndGet();
                    } catch (Exception e) {
                        // 유니크 제약 위반은 메시지가 길어서(SQL과 제약 이름이 붙는다) 클래스 이름만 센다
                        String key = e instanceof IllegalStateException
                                ? e.getClass().getSimpleName() + ": " + e.getMessage()
                                : e.getClass().getSimpleName();
                        failures.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                        if (!(e instanceof IllegalStateException)) {
                            System.out.println("[거점 동시 생성 예외] " + e.getMessage());
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).as("30초 안에 끝나야 한다").isTrue();
            pool.shutdown();

            Map<String, Integer> counted = new TreeMap<>();
            failures.forEach((k, v) -> counted.put(k, v.get()));
            long spots = pickupSpotRepository.findByUniversityId(university.getId()).stream()
                    .filter(spot -> spot.getName().equals(name))
                    .count();
            System.out.println("[거점 동시 생성 " + (round + 1) + "] 성공 " + succeeded.get()
                    + ", 만들어진 거점 " + spots + ", 실패 " + counted);

            assertThat(spots).as("만들어진 거점 수").isEqualTo(1);
            assertThat(succeeded.get()).as("성공한 요청 수").isEqualTo(1);
            // 유니크 제약 위반(500)이 아니라 한 명씩 만들었을 때와 같은 거절(409)이어야 한다
            assertThat(counted).as("실패 내역")
                    .isEqualTo(Map.of("IllegalStateException: 이미 있는 거점입니다", CREATORS - 1));
        }
    }
}
