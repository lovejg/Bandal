package com.bandal.user;

import com.bandal.auth.dto.UserResponse;
import com.bandal.common.NotFoundException;
import com.bandal.user.dto.RegisterAccountRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    // 송금받을 계좌 등록. 방을 만들기 전에 한 번 해두면 된다 (ADR-031)
    @Transactional
    public UserResponse registerAccount(Long userId, RegisterAccountRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다"));
        user.registerAccount(request.bankName(), request.accountNumber(), request.accountHolder());
        return UserResponse.of(user);
    }
}
