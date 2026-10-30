package com.bandal.auth.dto;

import com.bandal.user.User;

import java.time.Instant;

// 내 정보. 비밀번호 해시는 절대 나가지 않는다
public record UserResponse(
        Long id,
        String email,
        String nickname,
        Long universityId,
        String universityName,
        boolean emailVerified,
        int trustScore,
        // 계좌가 있는지만. 방 만들기 폼을 열기 전에 계좌 등록으로 보낼지 정하는 데 쓴다
        boolean hasAccount,
        // 닉네임을 다시 바꿀 수 있는 때. 한 번도 안 바꿨으면 null(지금 바로 바꿀 수 있다). 화면이 "○월 ○일부터"를 보여 준다
        Instant nicknameChangeableAt
) {

    public static UserResponse of(User user) {
        Instant changedAt = user.getNicknameChangedAt();
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getUniversity().getId(),
                user.getUniversity().getName(),
                user.getEmailVerifiedAt() != null,
                user.getTrustScore(),
                user.hasAccount(),
                changedAt == null ? null : changedAt.plus(User.NICKNAME_CHANGE_INTERVAL)
        );
    }
}
