package com.bandal.grouporder;

import com.bandal.grouporder.dto.GroupOrderListResponse;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

// 방 목록 첫 페이지를 학교별로 Redis에 잠깐 담아 둔다. 키는 rooms:{학교id} 하나다
// 캐시는 보여 주기용이다. 참여 같은 판단은 늘 DB에서 한다
// Redis가 안 되면 캐시가 없는 것으로 보고 넘어간다. 캐시 때문에 목록이나 참여가 실패하면 안 된다
@Component
@RequiredArgsConstructor
@Slf4j
public class RoomListCache {

    // 캐시하는 건 첫 페이지, 이 크기 하나뿐이다. 화면이 한 번에 받는 개수와 같다
    public static final int CACHED_SIZE = 20;

    private static final String KEY_PREFIX = "rooms:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    // 끄면 늘 DB에서 만든다. Phase 4 부하 테스트에서 켜고 끄며 비교했다
    @Value("${app.room-list-cache.enabled}")
    private boolean enabled;

    @Value("${app.room-list-cache.ttl-seconds}")
    private long ttlSeconds;

    // 이 요청을 캐시로 받을 수 있는지. 다른 페이지나 크기는 캐시를 거치지 않고 DB로 간다
    // 같은 키에 크기가 다른 결과가 섞이면 안 되기 때문이다
    public boolean covers(int page, int size) {
        return enabled && page == 0 && size == CACHED_SIZE;
    }

    // 담아 둔 첫 페이지. 없거나 Redis가 안 되면 null이고, 그러면 DB에서 만든다
    public GroupOrderListResponse get(Long universityId) {
        try {
            String json = redisTemplate.opsForValue().get(key(universityId));
            if (json == null) {
                count("miss");
                return null;
            }
            // 읽기에 성공한 것만 적중으로 센다. 모양이 깨진 값은 아래에서 error로 센다
            GroupOrderListResponse cached = objectMapper.readValue(json, GroupOrderListResponse.class);
            count("hit");
            return cached;
        } catch (DataAccessException | JacksonException e) {
            // 읽던 모양이 바뀐 옛 값(응답 DTO를 고친 직후)도 여기로 온다. 없는 것으로 본다
            log.warn("방 목록 캐시를 읽지 못했습니다. universityId={}, {}", universityId, e.toString());
            count("error");
            return null;
        }
    }

    // 캐시 적중률을 재려고 읽을 때마다 결과를 센다. Prometheus에서는 bandal_room_list_cache_total{result=...}로 보인다
    private void count(String result) {
        meterRegistry.counter("bandal.room_list_cache", "result", result).increment();
    }

    // DB에서 만든 첫 페이지를 담는다. TTL이 지나면 저절로 사라진다
    public void put(Long universityId, GroupOrderListResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(key(universityId), json, Duration.ofSeconds(ttlSeconds));
        } catch (DataAccessException | JacksonException e) {
            log.warn("방 목록 캐시를 넣지 못했습니다. universityId={}, {}", universityId, e.toString());
        }
    }

    // 그 학교의 목록이 바뀌었다. 지우기만 하고, 다음에 목록을 연 요청이 DB에서 새로 채운다
    // 트랜잭션 안이면 커밋 뒤로 미루고, 밖이면 바로 지운다
    public void evict(Long universityId) {
        if (!enabled) return;
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete(universityId);
                }
            });
            return;
        }
        delete(universityId);
    }

    // evict용 헬퍼
    private void delete(Long universityId) {
        try {
            redisTemplate.delete(key(universityId));
        } catch (DataAccessException e) {
            // 이미 커밋된 변경을 실패로 돌려주면 안 된다. 못 지운 캐시는 TTL이 치운다
            log.warn("방 목록 캐시를 지우지 못했습니다. universityId={}, {}", universityId, e.toString());
        }
    }

    private String key(Long universityId) {
        return KEY_PREFIX + universityId;
    }
}
