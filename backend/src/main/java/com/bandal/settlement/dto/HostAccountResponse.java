package com.bandal.settlement.dto;

import com.bandal.user.User;

// 송금할 계좌. 정산이 시작된 방의 참여자에게만 내려간다 (ADR-031)
// 예금주는 마스킹된 이름이다. 은행 앱에 뜨는 예금주와 대조해 계좌번호 오타를 잡는 용도다
public record HostAccountResponse(
        String bankName,
        String accountNumber,
        String accountHolder
) {

    public static HostAccountResponse of(User host) {
        return new HostAccountResponse(host.getBankName(), host.getAccountNumber(),
                host.maskedAccountHolder());
    }
}
