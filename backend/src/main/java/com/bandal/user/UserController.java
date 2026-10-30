package com.bandal.user;

import com.bandal.auth.dto.TokenResponse;
import com.bandal.user.dto.ChangeNicknameRequest;
import com.bandal.user.dto.ChangePasswordRequest;
import com.bandal.user.dto.RegisterAccountRequest;
import com.bandal.user.dto.WithdrawRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

    // 계좌 등록과 변경. 주소에 id 대신 me를 쓴다. 대상은 토큰의 주인뿐이라 남의 id를 넣을 자리를 만들지 않는다
    // 처음 등록이든 변경이든 "내 계좌를 이 값으로 둔다"라 PUT이다. 같은 요청을 두 번 보내도 결과가 같다
    // 본인이 방금 쓴 값이라 돌려줄 게 없어서 204
    @PutMapping("/me/account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void registerAccount(@AuthenticationPrincipal Long userId,
                                @Valid @RequestBody RegisterAccountRequest request) {
        userService.registerAccount(userId, request);
    }

    // 비밀번호 변경. 다른 기기의 리프레시 토큰은 무효가 되고, 이 기기에는 새 토큰 두 개를 돌려준다
    // 비밀번호라는 칸 하나만 바꾸므로 PATCH
    @PatchMapping("/me/password")
    public TokenResponse changePassword(@AuthenticationPrincipal Long userId,
                                        @Valid @RequestBody ChangePasswordRequest request) {
        return userService.changePassword(userId, request);
    }

    // 닉네임 변경. 바뀐 값은 화면이 내 정보를 다시 읽어 받는다
    @PatchMapping("/me/nickname")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeNickname(@AuthenticationPrincipal Long userId,
                               @Valid @RequestBody ChangeNicknameRequest request) {
        userService.changeNickname(userId, request);
    }

    // 탈퇴. 대상은 "나"라는 자원이라 DELETE /me. 확인용 비밀번호를 본문으로 받는다
    // 끝나면 이 기기의 토큰도 무효라, 화면은 저장해 둔 토큰을 지우고 첫 화면으로 간다
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@AuthenticationPrincipal Long userId,
                         @Valid @RequestBody WithdrawRequest request) {
        userService.withdraw(userId, request);
    }
}
