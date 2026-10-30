package com.bandal.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 재설정 메일의 링크에서 새 비밀번호를 정했다. 토큰은 링크 주소에 있던 값
public record PasswordResetConfirmRequest(

        @NotBlank(message = "링크가 올바르지 않습니다")
        String token,

        // 가입과 같은 규칙
        @NotBlank(message = "새 비밀번호를 적어주세요")
        @Size(min = 8, max = 64, message = "비밀번호는 8자에서 64자 사이여야 합니다")
        String newPassword
) {
}
