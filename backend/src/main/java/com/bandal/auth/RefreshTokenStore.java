package com.bandal.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

// 리프레시 토큰을 Redis에 둔다. 로그아웃은 여기서 지우는 것이다
// 저장 모양: "refresh:{토큰}" → "userId:버전". 버전은 사용자마다 하나("refresh-version:{userId}", 없으면 0)
// 비밀번호를 바꾸면 버전을 올린다. 그 전에 나간 토큰은 적힌 버전이 달라져 갱신이 거절된다(모든 기기 로그아웃)
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "refresh:";
    private static final String VERSION_PREFIX = "refresh-version:";

    private final StringRedisTemplate redisTemplate;

    @Value("${jwt.refresh-token-days}")
    private long refreshTokenDays;

    // 새 토큰을 만들어 저장한다. 토큰 자체는 의미 없는 난수다(불투명 토큰)
    // 값에 지금 버전을 같이 적어 둔다. 나중에 버전이 올라가면 이 토큰은 무효가 된다
    public String issue(Long userId) {
        String token = UUID.randomUUID().toString();
        String value = userId + ":" + currentVersion(userId);
        redisTemplate.opsForValue()
                .set(KEY_PREFIX + token, value, Duration.ofDays(refreshTokenDays));
        return token;
    }

    // 이 토큰이 누구 것인지. 없거나 만료됐거나 버전이 지났으면 null
    public Long findUserId(String token) {
        if (token == null) {
            return null;
        }
        return validOwner(redisTemplate.opsForValue().get(KEY_PREFIX + token));
    }

    // 갱신용. 이 토큰이 누구 것인지 꺼내면서 그 자리에서 지운다. Redis GETDEL 명령 하나라 사이에 끼어들 틈이 없다
    // 동시에 여러 번 불러도 값을 받는 건 한 번뿐이고, 나머지는 null을 받는다
    // 버전이 지난 토큰도 여기서 지워진다. 어차피 무효라 지워져도 잃는 게 없다
    public Long consume(String token) {
        if (token == null) {
            return null;
        }
        return validOwner(redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + token));
    }

    // 로그아웃. 지워지면 더는 갱신할 수 없다
    public void revoke(String token) {
        if (token != null) {
            redisTemplate.delete(KEY_PREFIX + token);
        }
    }

    // 이 사용자에게 지금까지 나간 리프레시 토큰을 모두 무효로 한다. 비밀번호를 바꿀 때 쓴다
    // 토큰을 하나하나 찾아 지우지 않고 버전만 올린다. 옛 토큰은 갱신 때 거절되고, 만료(2주)되면 Redis가 지운다
    // INCR은 키가 없으면 0으로 보고 1을 만든다. 명령 하나라 동시에 두 번 불러도 둘 다 반영된다
    // 버전 키에는 만료를 걸지 않는다. 키가 사라지면 버전이 0으로 돌아가 옛 토큰이 되살아날 수 있다
    public void revokeAll(Long userId) {
        redisTemplate.opsForValue().increment(VERSION_PREFIX + userId);
    }

    // 지금 버전. 비밀번호를 한 번도 안 바꾼 사용자는 키가 없어서 0
    private long currentVersion(Long userId) {
        String version = redisTemplate.opsForValue().get(VERSION_PREFIX + userId);
        return version == null ? 0 : Long.parseLong(version);
    }

    // 저장된 값("5:2")을 읽어 적힌 버전이 지금 버전과 같을 때만 사용자 id(5)를 돌려준다. 다르면 무효라 null
    // 버전을 적기 전에 나간 토큰은 값이 "5"뿐이다. 버전 0으로 본다. 안 그러면 배포하는 순간 모두가 로그아웃된다
    //
    // 비밀번호 변경과 갱신이 같은 순간에 겹치면, 갱신 쪽이 올리기 전 버전을 읽어 옛 버전 토큰을 하나 새로 받을 수 있다.
    // 그 토큰도 다음 갱신 때 여기서 거절된다. 빈틈이 생겨도 액세스 토큰 수명(15분) 안에서 스스로 닫힌다
    private Long validOwner(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split(":");          // "5:2" → ["5", "2"], "5" → ["5"]
        Long userId = Long.valueOf(parts[0]);
        long version = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
        return version == currentVersion(userId) ? userId : null;
    }
}
