package com.bandal.grouporder;

import com.bandal.grouporder.dto.GroupOrderListResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

// 캐시 클래스 혼자. Redis가 멈춘 상황은 진짜 Redis로 만들기 어려워서 가짜(mock)로 만든다
class RoomListCacheTest {

    StringRedisTemplate redisTemplate;
    ValueOperations<String, String> ops;
    RoomListCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);

        cache = new RoomListCache(redisTemplate, JsonMapper.builder().build());
        ReflectionTestUtils.setField(cache, "enabled", true);
        ReflectionTestUtils.setField(cache, "ttlSeconds", 10L);
    }

    RedisConnectionFailureException redisDown() {
        return new RedisConnectionFailureException("Redis에 연결할 수 없음");
    }

    @Test
    @DisplayName("첫 페이지, 크기 20만 캐시로 받는다")
    void coversOnlyFirstPageOfFixedSize() {
        assertThat(cache.covers(0, 20)).isTrue();
        assertThat(cache.covers(1, 20)).isFalse();
        assertThat(cache.covers(0, 30)).isFalse();
    }

    @Test
    @DisplayName("꺼 두면 아무 요청도 캐시로 받지 않고, 지울 때도 Redis를 부르지 않는다")
    void disabled() {
        ReflectionTestUtils.setField(cache, "enabled", false);

        assertThat(cache.covers(0, 20)).isFalse();
        cache.evict(1L);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("넣을 때 rooms:{학교id} 키에 TTL을 붙인다")
    void putsWithTtl() {
        cache.put(7L, new GroupOrderListResponse(List.of(), false));

        verify(ops).set(eq("rooms:7"), anyString(), eq(Duration.ofSeconds(10)));
    }

    @Test
    @DisplayName("Redis가 멈추면 읽기는 캐시가 없는 것(null)으로 돌려준다")
    void getReturnsNullWhenRedisDown() {
        when(ops.get("rooms:7")).thenThrow(redisDown());

        assertThat(cache.get(7L)).isNull();
    }

    @Test
    @DisplayName("담긴 값을 읽을 수 없는 모양이면 캐시가 없는 것으로 본다")
    void getReturnsNullOnBrokenValue() {
        // 응답 DTO를 고친 직후, 고치기 전 모양으로 담긴 값이 남아 있는 경우
        when(ops.get("rooms:7")).thenReturn("{\"rooms\": 3");

        assertThat(cache.get(7L)).isNull();
    }

    @Test
    @DisplayName("Redis가 멈춰도 넣기는 예외를 던지지 않는다")
    void putSwallowsRedisFailure() {
        doThrow(redisDown()).when(ops).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> cache.put(7L, new GroupOrderListResponse(List.of(), false)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Redis가 멈춰도 지우기는 예외를 던지지 않는다")
    void evictSwallowsRedisFailure() {
        // 참여는 이미 커밋됐다. 여기서 터지면 사용자는 성공한 참여를 500으로 받는다
        when(redisTemplate.delete("rooms:7")).thenThrow(redisDown());

        assertThatCode(() -> cache.evict(7L)).doesNotThrowAnyException();
    }

    // 아래는 트랜잭션 안에서 부른 경우. 스프링이 트랜잭션을 열 때 하는 일(동기화 켜기)을 손으로 하고,
    // 커밋이나 롤백 때 스프링이 부르는 콜백도 손으로 부른다
    void inTransaction(Runnable body) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            body.run();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    }

    void rollback() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    }

    @Test
    @DisplayName("트랜잭션 밖에서 부르면 그 자리에서 바로 지운다")
    void evictsImmediatelyWithoutTransaction() {
        cache.evict(7L);

        verify(redisTemplate).delete("rooms:7");
    }

    @Test
    @DisplayName("트랜잭션 안에서 부르면 그 자리에서는 안 지우고, 커밋된 뒤에 지운다")
    void evictsAfterCommit() {
        inTransaction(() -> {
            cache.evict(7L);
            // 아직 커밋 전이다. 여기서 지우면 다른 요청이 커밋 전 DB를 읽어 옛 목록을 다시 담는다
            verify(redisTemplate, never()).delete(anyString());

            commit();
            verify(redisTemplate).delete("rooms:7");
        });
    }

    @Test
    @DisplayName("롤백되면 지우지 않는다")
    void doesNotEvictOnRollback() {
        inTransaction(() -> {
            cache.evict(7L);
            rollback();
        });

        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("커밋 뒤에 지우다 Redis가 멈춰도 예외를 던지지 않는다")
    void afterCommitSwallowsRedisFailure() {
        // 커밋은 이미 끝났다. 여기서 터지면 사용자는 성공한 참여를 500으로 받는다
        when(redisTemplate.delete("rooms:7")).thenThrow(redisDown());

        inTransaction(() -> {
            cache.evict(7L);
            assertThatCode(this::commit).doesNotThrowAnyException();
        });
    }
}
