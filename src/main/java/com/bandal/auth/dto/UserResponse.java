package com.bandal.auth.dto;

import com.bandal.user.User;

// 가입 결과. 비밀번호 해시는 절대 나가지 않는다 (ADR-023)
public record UserResponse(
        Long id,
        String email,
        String nickname,
        Long universityId,
        String universityName,
        boolean emailVerified,
        int trustScore
) {

    public static UserResponse of(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getUniversity().getId(),
                user.getUniversity().getName(),
                user.getEmailVerifiedAt() != null,
                user.getTrustScore()
        );
    }
}
