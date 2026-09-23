package com.bandal.auth;

import com.bandal.auth.dto.LoginRequest;
import com.bandal.auth.dto.RefreshRequest;
import com.bandal.auth.dto.SignUpRequest;
import com.bandal.auth.dto.SignUpResponse;
import com.bandal.auth.dto.TokenResponse;
import com.bandal.auth.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;

    // 응답은 "메일을 보냈다"까지만 말한다. 계정이 생겼는지는 메일함을 봐야 안다 (ADR-028)
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SignUpResponse signUp(@Valid @RequestBody SignUpRequest request) {
        return authService.signUp(request);
    }

    // 내 정보. 프론트가 인증 여부를 확인할 때 쓴다
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Long userId) {
        return authService.me(userId);
    }

    // 메일 속 링크가 가리키는 곳. 메일 클라이언트는 POST를 못 해서 GET일 수밖에 없다 (ADR-027)
    // 브라우저로 여는 주소라 JSON 대신 짧은 HTML을 돌려준다
    @GetMapping(value = "/verify", produces = MediaType.TEXT_HTML_VALUE)
    public String verify(@RequestParam String token) {
        emailVerificationService.verify(token);
        return """
                <!doctype html>
                <html lang="ko"><meta charset="utf-8"><title>반달</title>
                <body style="font-family:sans-serif;text-align:center;padding:80px">
                <h1>이메일 인증이 끝났습니다</h1>
                <p>이제 공구방을 만들고 참여할 수 있어요.</p>
                </body></html>
                """;
    }

    // 메일이 안 왔을 때 다시 보내기. 로그인은 되어 있어야 한다(미인증이어도 된다)
    @PostMapping("/verify/resend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendVerification(@AuthenticationPrincipal Long userId) {
        emailVerificationService.resend(userId);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    // 액세스 토큰이 만료됐을 때. 다시 로그인하지 않고 새로 받는다
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }
}
