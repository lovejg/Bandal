package com.bandal.user;

import com.bandal.common.NotFoundException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
    boolean existsByNickname(String nickname);

    // 없으면 404. 토큰은 멀쩡한데 계정이 지워진 경우 같은 것
    default User findByIdOrThrow(Long id) {
        return findById(id).orElseThrow(() -> new NotFoundException("없는 사용자입니다"));
    }
}
