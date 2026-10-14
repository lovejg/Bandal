package com.bandal.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 가입 요청. 받는 정보는 셋뿐이다. 개인정보는 갖고 있으면 지킬 의무가 생긴다 (ADR-025)
public record SignUpRequest(

        @NotBlank(message = "이메일을 적어주세요")
        @Email(message = "이메일 형식이 아닙니다")
        String email,

        @NotBlank(message = "비밀번호를 적어주세요")
        @Size(min = 8, max = 64, message = "비밀번호는 8자 이상이어야 합니다")
        String password,

        @NotBlank(message = "닉네임을 적어주세요")
        @Size(min = 2, max = 20, message = "닉네임은 2자에서 20자 사이여야 합니다")
        String nickname
) {
}
