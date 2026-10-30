package com.bandal.user;

import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.JwtProvider;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.participation.OrderItemRepository;
import com.bandal.participation.ParticipationRepository;
import com.bandal.pickupspot.PickupSpotRepository;
import com.bandal.settlement.SettlementRepository;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// 두 사람이 같은 닉네임을 동시에 차지하려 한다. "먼저 있는지 묻고 저장" 사이에 끼어들면 둘 다 물음을 통과한다.
// 락으로는 못 막는다(서로 다른 사용자의 줄을 고친다). 막는 건 DB의 unique 제약이고, 그 거부가 409로 나가야 한다(500이 아니라)
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NicknameRaceTest {

    static final int ROUNDS = 20;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtProvider jwtProvider;

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

    University hankuk;

    @BeforeEach
    void setUp() {
        settlementRepository.deleteAll();
        orderItemRepository.deleteAll();
        participationRepository.deleteAll();
        groupOrderRepository.deleteAll();
        userRepository.deleteAll();
        pickupSpotRepository.deleteAll();
        universityRepository.deleteAll();
        hankuk = universityRepository.save(new University("한국대학교", "hankuk.ac.kr"));
    }

    User saveUser(String email, String nickname) {
        User user = new User(hankuk, email, "hash", nickname);
        ReflectionTestUtils.setField(user, "emailVerifiedAt", Instant.now());
        return userRepository.save(user);
    }

    // 두 요청을 한 출발선에서 동시에 보내고 응답 코드 두 개를 받는다
    List<Integer> fireTogether(RequestBuilder a, RequestBuilder b) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
        for (RequestBuilder request : List.of(a, b)) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    statuses.add(mockMvc.perform(request).andReturn().getResponse().getStatus());
                } catch (Exception e) {
                    statuses.add(-1);
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        Collections.sort(statuses);
        return statuses;
    }

    @Test
    @DisplayName("두 사람이 같은 닉네임으로 동시에 바꾸면 한 명만 성공(204)하고 다른 한 명은 409다")
    void concurrentNicknameChange() throws InterruptedException {
        List<String> broken = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            User a = saveUser("a" + round + "@hankuk.ac.kr", "가" + round);
            User b = saveUser("b" + round + "@hankuk.ac.kr", "나" + round);
            String wanted = "경쟁닉" + round;

            List<Integer> statuses = fireTogether(changeNickname(a, wanted), changeNickname(b, wanted));

            System.out.println("[닉네임 동시 변경 " + (round + 1) + "] " + statuses);
            if (!statuses.equals(List.of(204, 409))) broken.add("회차 " + (round + 1) + ": " + statuses);
        }
        assertThat(broken).as("204 하나, 409 하나가 아닌 회차").isEmpty();
    }

    @Test
    @DisplayName("두 사람이 같은 닉네임으로 동시에 가입하면 한 명만 가입(202)되고 다른 한 명은 409다")
    void concurrentSignUpWithSameNickname() throws InterruptedException {
        List<String> broken = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            String wanted = "가입경쟁" + round;

            List<Integer> statuses = fireTogether(
                    signUp("x" + round + "@hankuk.ac.kr", wanted),
                    signUp("y" + round + "@hankuk.ac.kr", wanted));

            System.out.println("[같은 닉네임 동시 가입 " + (round + 1) + "] " + statuses);
            if (!statuses.equals(List.of(202, 409))) broken.add("회차 " + (round + 1) + ": " + statuses);
        }
        assertThat(broken).as("202 하나, 409 하나가 아닌 회차").isEmpty();
    }

    RequestBuilder changeNickname(User user, String nickname) {
        return patch("/api/users/me/nickname")
                .header("Authorization", "Bearer " + jwtProvider.createAccessToken(user.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"nickname": "%s"}
                        """.formatted(nickname));
    }

    RequestBuilder signUp(String email, String nickname) {
        return post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "password1234", "nickname": "%s", "universityId": %d}
                        """.formatted(email, nickname, hankuk.getId()));
    }
}
