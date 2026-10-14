package com.bandal.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

// 리프레시 토큰을 Redis에 둔다. 로그아웃은 여기서 지우는 것이다
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "refresh:";

    private final StringRedisTemplate redisTemplate;

    @Value("${jwt.refresh-token-days}")
    private long refreshTokenDays;

    // 새 토큰을 만들어 저장한다. 값은 의미 없는 난수다(불투명 토큰)
    public String issue(Long userId) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue()
                .set(KEY_PREFIX + token, String.valueOf(userId), Duration.ofDays(refreshTokenDays));
        return token;
    }

    // 이 토큰이 누구 것인지. 없거나 만료됐으면 null
    public Long findUserId(String token) {
        if (token == null) {
            return null;
        }
        String userId = redisTemplate.opsForValue().get(KEY_PREFIX + token);
        return userId == null ? null : Long.valueOf(userId);
    }

    // 로그아웃. 지워지면 더는 갱신할 수 없다
    public void revoke(String token) {
        if (token != null) {
            redisTemplate.delete(KEY_PREFIX + token);
        }
    }
}
