package com.bandal.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

// 이메일 인증 토큰을 Redis에 둔다. 만료는 TTL이 알아서 해준다
// RefreshTokenStore와 하는 일이 닮았다. 다른 점은 한 번 쓰면 사라진다는 것
@Component
@RequiredArgsConstructor
public class EmailVerificationStore {

    // verify:{토큰} -> 사용자 id
    private static final String TOKEN_PREFIX = "verify:";
    // verify:resend:{사용자 id} -> 이번 창에서 보낸 횟수
    private static final String RESEND_PREFIX = "verify:resend:";

    private final StringRedisTemplate redisTemplate;

    @Value("${app.verification-token-minutes}")
    private long tokenMinutes;

    @Value("${app.resend-window-minutes}")
    private long resendWindowMinutes;

    // 새 인증 토큰을 만들어 저장하고 돌려준다
    // 값은 리프레시 토큰과 같은 불투명 토큰이다
    public String issue(Long userId) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue()
            .set(TOKEN_PREFIX + token, String.valueOf(userId), Duration.ofMinutes(tokenMinutes));
        return token;
    }

    // 토큰을 쓴다. 누구 것인지 돌려주고 키는 지운다. 없으면 null.
    // 만료됐든, 이미 썼든, 지어낸 값이든 전부 null로 같다. 구분해서 알려주면 실재했던 토큰임을 알려주는 셈이다
    public Long consume(String token) {
        if(token == null) return null;
        String userId = redisTemplate.opsForValue().getAndDelete(TOKEN_PREFIX + token);
        return userId == null ? null : Long.valueOf(userId);
    }

    // 재전송 횟수를 1 올리고 올린 뒤의 값을 돌려준다.
    // 창(resendWindowMinutes) 안에서 누적되고, 창이 지나면 키가 사라져 0부터 다시 센다. (고정 창 방식)
    public long increaseResendCount(Long userId) {
        String key = RESEND_PREFIX + userId;
        Long count = redisTemplate.opsForValue().increment(key);
        // 처음 올릴 경우 TTL 걸기
        if(count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofMinutes(resendWindowMinutes));
        }

        return count == null ? 0 : count;
    }
}
