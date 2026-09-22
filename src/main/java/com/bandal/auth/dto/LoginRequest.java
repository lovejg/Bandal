package com.bandal.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(

        @NotBlank(message = "이메일을 적어주세요")
        String email,

        @NotBlank(message = "비밀번호를 적어주세요")
        String password
) {
}
