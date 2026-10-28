package com.bandal.outbox;

import com.bandal.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// 찜 쿼리를 진짜 Postgres에 돌린다
// @DataJpaTest는 테스트마다 트랜잭션 하나로 감싸는데, 여기서는 트랜잭션 둘이 서로 부딪히는 걸 봐야 한다.
// 그래서 감싸지 않게 하고(NOT_SUPPORTED) 트랜잭션은 테스트가 직접 연다
@DataJpaTest
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxMessageRepositoryTest {

    static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    @Autowired
    OutboxMessageRepository outboxMessageRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void tearDown() {
        outboxMessageRepository.deleteAll();
    }

    // 새 줄은 nextAttemptAt이 만든 시각이다. 시각을 다르게 주면 차례가 먼저 된 순서가 정해진다
    OutboxMessage pending(long userId, Instant createdAt) {
        return outboxMessageRepository.save(OutboxMessage.mail(OutboxType.VERIFICATION_MAIL, userId, createdAt));
    }

    @Test
    @DisplayName("방 상태 줄을 진짜 DB에 저장하고 다시 읽으면 방 id와 내용(jsonb)이 그대로다")
    void savesGroupOrderStatusRow() {
        String payload = "{\"groupOrderId\": 3, \"to\": \"CLOSED\", \"participants\": [{\"userId\": 7, \"totalAmount\": 13000}]}";

        Long id = outboxMessageRepository.save(OutboxMessage.groupOrderStatus(3L, payload, NOW)).getId();

        OutboxMessage found = outboxMessageRepository.findById(id).orElseThrow();
        assertThat(found.getType()).isEqualTo(OutboxType.GROUP_ORDER_STATUS_CHANGED);
        assertThat(found.getGroupOrderId()).isEqualTo(3L);
        assertThat(found.getUserId()).isNull();
        // jsonb는 저장할 때 띄어쓰기와 키 순서를 자기 식으로 바꾼다("to"가 "groupOrderId"보다 앞에 온다). 글자 대신 JSON 내용이 같은지 본다
        ObjectMapper objectMapper = new ObjectMapper();
        assertThat(objectMapper.readTree(found.getPayload())).isEqualTo(objectMapper.readTree(payload));
    }

    List<Long> ids(List<OutboxMessage> messages) {
        return messages.stream().map(OutboxMessage::getId).toList();
    }

    @Test
    @DisplayName("지금 보낼 수 있는 대기 줄만, 차례가 먼저 된 것부터 limit개 가져온다")
    void picksDuePendingOldestFirst() {
        OutboxMessage oldest = pending(1L, NOW.minusSeconds(30));
        OutboxMessage older = pending(2L, NOW.minusSeconds(20));
        pending(3L, NOW.minusSeconds(10));
        // 아직 차례가 아니다(찜해 두었거나 재시도 대기)
        pending(4L, NOW.plusSeconds(60));
        // 이미 보냈다
        OutboxMessage sent = pending(5L, NOW.minusSeconds(40));
        sent.claim(NOW);
        sent.markSent(NOW);
        outboxMessageRepository.save(sent);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        List<OutboxMessage> picked = tx.execute(status -> outboxMessageRepository.findDueForUpdate(NOW, 2));

        assertThat(ids(picked)).containsExactly(oldest.getId(), older.getId());
    }

    @Test
    @DisplayName("다른 집배원이 잠근 줄은 기다리지 않고 건너뛰어, 둘이 서로 다른 줄을 가져간다")
    void skipsRowsLockedByAnother() throws Exception {
        for (int i = 0; i < 4; i++) {
            pending(i, NOW.minusSeconds(40 - i));
        }

        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        // 첫 집배원: 줄 2개를 잠근 채 트랜잭션을 열어 둔다
        CompletableFuture<List<Long>> first = CompletableFuture.supplyAsync(() ->
                new TransactionTemplate(transactionManager).execute(status -> {
                    List<OutboxMessage> picked = outboxMessageRepository.findDueForUpdate(NOW, 2);
                    firstLocked.countDown();
                    await(releaseFirst);
                    return ids(picked);
                }));
        assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();

        // 두 번째 집배원: 첫 집배원이 아직 잠그고 있는 동안 같은 쿼리를 부른다
        CompletableFuture<List<Long>> second = CompletableFuture.supplyAsync(() ->
                new TransactionTemplate(transactionManager).execute(status ->
                        ids(outboxMessageRepository.findDueForUpdate(NOW, 2))));

        // 건너뛰면 바로 돌아온다. 기다리는 락이면 첫 집배원이 놓을 때까지 여기서 멈춘다
        List<Long> secondIds;
        try {
            secondIds = second.get(3, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }
        List<Long> firstIds = first.get(10, TimeUnit.SECONDS);

        assertThat(firstIds).hasSize(2);
        assertThat(secondIds).hasSize(2);
        assertThat(secondIds).doesNotContainAnyElementsOf(firstIds);
    }

    static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
