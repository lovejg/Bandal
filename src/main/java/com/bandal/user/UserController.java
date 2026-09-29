package com.bandal.user;

import com.bandal.auth.dto.UserResponse;
import com.bandal.user.dto.RegisterAccountRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // 계좌는 내 것만 등록할 수 있다. 대상 id를 받지 않고 토큰에서 꺼낸다
    @PutMapping("/me/account")
    public UserResponse registerAccount(@AuthenticationPrincipal Long userId,
                                       @Valid @RequestBody RegisterAccountRequest request) {
        return userService.registerAccount(userId, request);
    }
}
