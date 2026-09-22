package com.bandal.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);
    Optional<User> findById(Long id);

    // 가입할 때 이메일과 닉네임 중복을 미리 본다. DB unique 제약에만 맡기면 500이 나간다
    boolean existsByEmail(String email);
    boolean existsByNickname(String nickname);
}
