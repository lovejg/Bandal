package com.bandal.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 비밀번호 변경. 현재 비밀번호로 본인임을 다시 확인한다. 열려 있는 남의 기기에서 새 비밀번호만으로 계정을 가로채지 못하게
public record ChangePasswordRequest(

        @NotBlank(message = "현재 비밀번호를 적어주세요")
        String currentPassword,

        // 가입과 같은 규칙
        @NotBlank(message = "새 비밀번호를 적어주세요")
        @Size(min = 8, max = 64, message = "비밀번호는 8자에서 64자 사이여야 합니다")
        String newPassword
) {
}
