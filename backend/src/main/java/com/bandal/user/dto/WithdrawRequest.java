package com.bandal.user.dto;

import jakarta.validation.constraints.NotBlank;

// 탈퇴. 비밀번호로 본인임을 다시 확인한다. 열려 있는 남의 기기에서 계정을 없애지 못하게
public record WithdrawRequest(

        @NotBlank(message = "비밀번호를 적어주세요")
        String password
) {
}
