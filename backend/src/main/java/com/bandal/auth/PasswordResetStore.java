package com.bandal.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

// 비밀번호 재설정 토큰을 Redis에 둔다. EmailVerificationStore와 같은 모양이다(한 번 쓰면 사라지고, 만료는 TTL이 한다)
@Component
@RequiredArgsConstructor
public class PasswordResetStore {

    // reset:{토큰} -> 사용자 id
    private static final String TOKEN_PREFIX = "reset:";
    // reset:requested:{사용자 id} -> 있으면 최근에 요청했다(1분 동안)
    private static final String REQUESTED_PREFIX = "reset:requested:";
    private static final Duration REQUEST_INTERVAL = Duration.ofMinutes(1);

    private final StringRedisTemplate redisTemplate;

    @Value("${app.password-reset-minutes}")
    private long tokenMinutes;

    public String issue(Long userId) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue()
                .set(TOKEN_PREFIX + token, String.valueOf(userId), Duration.ofMinutes(tokenMinutes));
        return token;
    }

    // 토큰을 쓴다. 누구 것인지 돌려주고 그 자리에서 지운다(GETDEL). 없거나 만료됐거나 이미 썼으면 null
    // 동시에 두 번 눌러도 값을 받는 건 한쪽뿐이다
    public Long consume(String token) {
        if (token == null) return null;
        String userId = redisTemplate.opsForValue().getAndDelete(TOKEN_PREFIX + token);
        return userId == null ? null : Long.valueOf(userId);
    }

    // 1분에 한 번만 메일을 보내게 자리를 잡는다. 잡았으면 true
    // SET NX(없을 때만 넣기)라 동시에 두 번 눌러도 한쪽만 true다. 1분 뒤 키가 사라지면 다시 잡을 수 있다
    public boolean tryAcquireRequestSlot(Long userId) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(REQUESTED_PREFIX + userId, "1", REQUEST_INTERVAL);
        return Boolean.TRUE.equals(acquired);
    }
}
