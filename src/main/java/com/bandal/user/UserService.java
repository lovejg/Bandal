package com.bandal.user;

import com.bandal.common.NotFoundException;
import com.bandal.grouporder.GroupOrderRepository;
import com.bandal.grouporder.GroupOrderStatus;
import com.bandal.user.dto.RegisterAccountRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 사용자 프로필을 고친다. 지금은 송금받을 계좌 하나
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final GroupOrderRepository groupOrderRepository;

    // 계좌 등록 및 변경
    @Transactional
    public void registerAccount(Long userId, RegisterAccountRequest request) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("없는 사용자입니다"));
        if(groupOrderRepository.existsByHostIdAndStatus(userId, GroupOrderStatus.SETTLING)) {
            throw new IllegalStateException("정산 중인 방이 있어 계좌를 수정할 수 없습니다");
        }
        user.registerAccount(request.bankName(), request.accountNumber(), request.accountHolder());
    }
}
