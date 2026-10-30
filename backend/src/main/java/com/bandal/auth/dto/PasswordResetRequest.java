package com.bandal.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// 비밀번호를 잊었다. 가입한 학교 메일을 받는다
public record PasswordResetRequest(

        @NotBlank(message = "이메일을 적어주세요")
        @Email(message = "이메일 형식이 아닙니다")
        String email
) {
}
