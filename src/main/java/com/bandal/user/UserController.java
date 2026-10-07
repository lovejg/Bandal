package com.bandal.user;

import com.bandal.user.dto.RegisterAccountRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // 계좌 등록과 변경. 주소에 id 대신 me를 쓴다. 대상은 토큰의 주인뿐이라 남의 id를 넣을 자리를 만들지 않는다 (ADR-024)
    // 처음 등록이든 변경이든 "내 계좌를 이 값으로 둔다"라 PUT이다. 같은 요청을 두 번 보내도 결과가 같다
    // 본인이 방금 쓴 값이라 돌려줄 게 없어서 204 (ADR-041)
    @PutMapping("/me/account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void registerAccount(@AuthenticationPrincipal Long userId,
                                @Valid @RequestBody RegisterAccountRequest request) {
        userService.registerAccount(userId, request);
    }
}
