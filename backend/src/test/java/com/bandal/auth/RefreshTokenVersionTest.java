package com.bandal.auth;

import com.bandal.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

// 사용자별 버전으로 그 사용자의 리프레시 토큰을 한꺼번에 무효로 한다(비밀번호 변경 때)
// 사용자 확인은 저장소가 하지 않아서 DB에 사용자를 만들 필요가 없다. 테스트끼리 부딪히지 않게 id를 크게 뽑는다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RefreshTokenVersionTest {

    @Autowired
    RefreshTokenStore refreshTokenStore;

    @Autowired
    StringRedisTemplate redisTemplate;

    long newUserId() {
        return ThreadLocalRandom.current().nextLong(1_000_000, 1_000_000_000);
    }

    @Test
    @DisplayName("revokeAll 전에 받은 토큰으로는 갱신할 수 없다")
    void revokeAllInvalidatesEarlierTokens() {
        long userId = newUserId();
        String pc = refreshTokenStore.issue(userId);
        String phone = refreshTokenStore.issue(userId);

        refreshTokenStore.revokeAll(userId);

        assertThat(refreshTokenStore.consume(pc)).isNull();
        assertThat(refreshTokenStore.consume(phone)).isNull();
    }

    @Test
    @DisplayName("revokeAll 뒤에 새로 받은 토큰으로는 갱신된다")
    void tokenIssuedAfterRevokeAllWorks() {
        long userId = newUserId();
        refreshTokenStore.issue(userId);

        refreshTokenStore.revokeAll(userId);
        String fresh = refreshTokenStore.issue(userId);

        assertThat(refreshTokenStore.consume(fresh)).isEqualTo(userId);
    }

    @Test
    @DisplayName("갱신해서 받은 토큰도 다시 갱신할 수 있다(버전이 이어진다)")
    void rotatedTokenKeepsVersion() {
        long userId = newUserId();
        refreshTokenStore.revokeAll(userId); // 버전이 0이 아닐 때도 이어지는지 본다
        String first = refreshTokenStore.issue(userId);

        Long owner = refreshTokenStore.consume(first);
        String second = refreshTokenStore.issue(owner);

        assertThat(refreshTokenStore.consume(second)).isEqualTo(userId);
    }

    @Test
    @DisplayName("다른 사용자의 토큰은 그대로 쓸 수 있다")
    void otherUsersAreUntouched() {
        long me = newUserId();
        long other = newUserId();
        String othersToken = refreshTokenStore.issue(other);

        refreshTokenStore.revokeAll(me);

        assertThat(refreshTokenStore.consume(othersToken)).isEqualTo(other);
    }

    @Test
    @DisplayName("무효가 된 토큰은 누구 것인지 묻는 findUserId에서도 null이다")
    void findUserIdChecksVersion() {
        long userId = newUserId();
        String token = refreshTokenStore.issue(userId);

        refreshTokenStore.revokeAll(userId);

        assertThat(refreshTokenStore.findUserId(token)).isNull();
    }

    @Test
    @DisplayName("revokeAll을 두 번 해도 그 뒤에 받은 토큰은 쓸 수 있다")
    void revokeAllTwice() {
        long userId = newUserId();
        refreshTokenStore.revokeAll(userId);
        refreshTokenStore.revokeAll(userId);

        String token = refreshTokenStore.issue(userId);

        assertThat(refreshTokenStore.consume(token)).isEqualTo(userId);
    }

    @Test
    @DisplayName("배포 전에 버전 없이 저장된 토큰(값이 userId뿐)은 버전 0으로 읽어 그대로 갱신된다")
    void legacyTokenWithoutVersionStillWorks() {
        long userId = newUserId();
        String legacy = "legacy-" + userId;
        redisTemplate.opsForValue().set("refresh:" + legacy, String.valueOf(userId));

        assertThat(refreshTokenStore.consume(legacy)).isEqualTo(userId);
    }

    @Test
    @DisplayName("버전 없는 옛 토큰도 revokeAll 뒤에는 무효다")
    void legacyTokenIsRevokedToo() {
        long userId = newUserId();
        String legacy = "legacy-" + userId;
        redisTemplate.opsForValue().set("refresh:" + legacy, String.valueOf(userId));

        refreshTokenStore.revokeAll(userId);

        assertThat(refreshTokenStore.consume(legacy)).isNull();
    }
}
