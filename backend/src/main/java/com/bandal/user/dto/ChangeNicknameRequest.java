package com.bandal.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 닉네임 변경. 가입과 같은 규칙(2~20자)
public record ChangeNicknameRequest(

        @NotBlank(message = "닉네임을 적어주세요")
        @Size(min = 2, max = 20, message = "닉네임은 2자에서 20자 사이여야 합니다")
        String nickname
) {
}
