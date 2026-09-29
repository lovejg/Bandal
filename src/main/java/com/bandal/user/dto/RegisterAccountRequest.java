package com.bandal.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 송금받을 계좌 등록. 방장이 되려면 있어야 한다
public record RegisterAccountRequest(

        @NotBlank(message = "은행을 적어주세요")
        @Size(max = 20, message = "은행 이름이 너무 깁니다")
        String bankName,

        @NotBlank(message = "계좌번호를 적어주세요")
        @Size(max = 30, message = "계좌번호가 너무 깁니다")
        @Pattern(regexp = "^[0-9\\- ]+$")
        String accountNumber,

        @NotBlank(message = "예금주를 적어주세요")
        @Size(max = 20, message = "예금주 이름이 너무 깁니다")
        String accountHolder
) {
}
