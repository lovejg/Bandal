package com.bandal.auth;

import com.bandal.auth.dto.LoginRequest;
import com.bandal.auth.dto.MessageResponse;
import com.bandal.auth.dto.PasswordResetConfirmRequest;
import com.bandal.auth.dto.PasswordResetRequest;
import com.bandal.auth.dto.RefreshRequest;
import com.bandal.auth.dto.SignUpRequest;
import com.bandal.auth.dto.SignUpResponse;
import com.bandal.auth.dto.TokenResponse;
import com.bandal.auth.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final PasswordResetService passwordResetService;

    // 비밀번호를 잊었다. 가입된 주소든 아니든 같은 202와 같은 문장으로 답한다(가입처럼 "메일을 보냈다"까지만)
    // 로그인 전에 부르는 곳이라 /api/auth/** permitAll에 들어 있다
    @PostMapping("/password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.request(request.email());
        return new MessageResponse(PasswordResetService.REQUEST_MESSAGE);
    }

    // 메일 링크의 화면에서 새 비밀번호를 정했다. 끝나면 모든 기기가 로그아웃되고, 화면은 로그인으로 보낸다
    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.confirm(request.token(), request.newPassword());
    }

    // 응답은 "메일을 보냈다"까지만 말한다. 계정이 생겼는지는 메일함을 봐야 안다
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

    // 메일 속 링크가 가리키는 곳. 메일 클라이언트는 POST를 못 해서 GET일 수밖에 없다
    // 브라우저로 여는 주소라 JSON 대신 짧은 HTML을 돌려준다
    // 실패도 HTML로 답한다. 공통 예외 처리로 보내면 브라우저에 JSON 글자가 그대로 뜬다
    @GetMapping(value = "/verify", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> verify(@RequestParam String token) {
        try {
            emailVerificationService.verify(token);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(verifyPage(e.getMessage(), "로그인한 뒤 내 화면에서 메일을 다시 받을 수 있어요."));
        }
        return ResponseEntity.ok(verifyPage("이메일 인증이 끝났습니다", "이제 공구방을 만들고 참여할 수 있어요."));
    }

    // 메일 링크로 여는 작은 화면. 문구는 우리가 정한 것만 들어가서 따로 이스케이프하지 않는다
    private static String verifyPage(String title, String body) {
        return """
                <!doctype html>
                <html lang="ko"><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1"><title>반달</title>
                <link rel="stylesheet" href="https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/variable/pretendardvariable-dynamic-subset.min.css">
                <body style="margin:0;font-family:'Pretendard Variable',-apple-system,'Apple SD Gothic Neo','Malgun Gothic',sans-serif;background:#f6f3ec;color:#1c1b19;text-align:center;padding:80px 18px">
                <h1 style="font-size:24px">%s</h1>
                <p style="color:#6e6a61">%s</p>
                <p style="margin-top:28px"><a href="/" style="display:inline-block;padding:13px 22px;border-radius:4px;background:#e8590c;color:#fff;font-weight:700;text-decoration:none">반달로 가기</a></p>
                </body></html>
                """.formatted(title, body);
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
