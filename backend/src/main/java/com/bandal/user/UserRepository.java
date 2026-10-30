package com.bandal.user;

import com.bandal.common.NotFoundException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByNickname(String nickname);

    // 점수가 낮은 채 탈퇴한 같은 메일(해시)의 가장 낮은 점수. 없으면 null
    // 여러 번 탈퇴, 재가입했으면 그중 가장 낮은 점수를 이어받는다
    @Query("SELECT MIN(u.trustScore) FROM User u WHERE u.withdrawnEmailHash = :hash")
    Integer findLowestWithdrawnTrustScore(@Param("hash") String hash);

    // 없으면 404. 토큰은 멀쩡한데 계정이 지워진 경우 같은 것
    default User findByIdOrThrow(Long id) {
        return findById(id).orElseThrow(() -> new NotFoundException("없는 사용자입니다"));
    }
}
