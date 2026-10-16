package com.bandal.auth;

import com.bandal.TestcontainersConfiguration;
import com.bandal.auth.dto.TokenResponse;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 같은 리프레시 토큰으로 갱신이 동시에 여러 번 들어온다 (Phase 3)
// 토큰은 한 번만 쓸 수 있어야 한다. 그래야 훔친 토큰과 진짜 주인 중 한쪽이 쓰면 다른 쪽이 막힌다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RefreshConcurrencyTest {

    static final int REQUESTS = 10;

    @Autowired
    AuthService authService;

    @Autowired
    RefreshTokenStore refreshTokenStore;

    @Test
    @DisplayName("같은 리프레시 토큰으로 10번 동시에 갱신하면 한 번만 성공하고 살아 있는 새 토큰도 하나다")
    void sameTokenRefreshesOnlyOnce() throws InterruptedException {
        List<String> broken = new ArrayList<>();
        for (int round = 0; round < 10; round++) {
            // 사용자 확인은 refresh가 하지 않아서 DB에 사용자를 만들 필요가 없다
            String token = refreshTokenStore.issue(1L);

            ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
            CountDownLatch ready = new CountDownLatch(REQUESTS);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(REQUESTS);
            List<String> issued = new CopyOnWriteArrayList<>();
            Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();

            for (int i = 0; i < REQUESTS; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        TokenResponse response = authService.refresh(token);
                        issued.add(response.refreshToken());
                    } catch (Exception e) {
                        String key = e.getClass().getSimpleName() + ": " + e.getMessage();
                        failures.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.await();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).as("30초 안에 끝나야 한다").isTrue();
            pool.shutdown();

            // 받은 새 토큰 중 지금도 갱신에 쓸 수 있는 것
            long alive = issued.stream().filter(t -> refreshTokenStore.findUserId(t) != null).count();
            Map<String, Integer> counted = new TreeMap<>();
            failures.forEach((k, v) -> counted.put(k, v.get()));
            System.out.println("[동시 갱신 " + (round + 1) + "] 성공 " + issued.size()
                    + ", 살아 있는 새 토큰 " + alive + ", 실패 " + counted);
            if (issued.size() != 1 || alive != 1) {
                broken.add("회차 " + (round + 1) + ": 성공 " + issued.size() + ", 살아 있는 새 토큰 " + alive);
            }
        }

        assertThat(broken).as("깨진 회차").isEmpty();
    }
}
