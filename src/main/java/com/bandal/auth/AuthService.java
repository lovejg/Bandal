package com.bandal.auth;

import com.bandal.auth.dto.LoginRequest;
import com.bandal.auth.dto.SignUpRequest;
import com.bandal.auth.dto.TokenResponse;
import com.bandal.auth.dto.UserResponse;
import com.bandal.common.UnauthorizedException;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UniversityRepository universityRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final RefreshTokenStore refreshTokenStore;

    // 계정 열거 방지용 가짜 해시
    // 사용자를 못 찾았을 때도 이걸로 대조를 한 번 돌려서 응답 시간을 맞춘다
    // 없는 이메일만 빨리 실패하면 시간만 재봐도 가입 여부를 알 수 있다
    private String dummyHash;

    @PostConstruct
    void initDummyHash() {
        dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public UserResponse signUp(SignUpRequest request) {

        String email = request.email();
        String password = request.password();
        String nickname = request.nickname();
        String domain = email.substring(email.indexOf('@') + 1).toLowerCase();
        University university = universityRepository.findByEmailDomain(domain)
            .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 학교 이메일입니다"));

        if(userRepository.existsByEmail(email)) {
            throw new IllegalStateException("이미 쓰이고 있는 이메일입니다");
        }
        else if(userRepository.existsByNickname(nickname)) {
            throw new IllegalStateException("이미 쓰이고 있는 닉네임입니다");
        }
        String hashed = passwordEncoder.encode(password);
        User user = new User(university, email, hashed, nickname);
        userRepository.save(user);
        return UserResponse.of(user);
    }

    // 로그인. 실패 이유는 구분하지 않는다
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email()).orElse(null);

        if (!matchesPassword(user, request.password())) {
            throw new UnauthorizedException("이메일 또는 비밀번호가 올바르지 않습니다");
        }

        return new TokenResponse(jwtProvider.createAccessToken(user.getId()),
                refreshTokenStore.issue(user.getId()));
    }

    // 액세스 토큰이 만료됐을 때 새로 받는다. 다시 로그인하지 않아도 된다
    public TokenResponse refresh(String refreshToken) {
        Long userId = refreshTokenStore.findUserId(refreshToken); // 찾고
        if(userId == null) {
            throw new UnauthorizedException("다시 로그인해주세요");
        }
        refreshTokenStore.revoke(refreshToken); // 폐기하고
        return new TokenResponse(jwtProvider.createAccessToken(userId), refreshTokenStore.issue(userId)); // 발급하기
    }

    // 로그아웃. 리프레시 토큰을 지우면 더는 갱신할 수 없다
    // 이미 발급된 액세스 토큰은 만료(15분)까지 살아 있다
    // 없는 토큰이여도 조용히 있는다(정보가 새지 않기 위해)
    public void logout(String refreshToken) {
        refreshTokenStore.revoke(refreshToken);
    }


    // 사용자가 없어도 더미 해시로 대조를 한 번 돌린다.
    // 없는 이메일만 빨리 실패하면 응답 시간만 재봐도 가입 여부를 알 수 있다
    private boolean matchesPassword(User user, String rawPassword) {
        String hash = (user == null) ? dummyHash : user.getPassword();
        // 순서를 바꾸면 안 된다. user != null을 앞에 두면 단축 평가 때문에 없는 이메일일 때 대조를 건너뛰어서 위의 방어가 사라진다
        return passwordEncoder.matches(rawPassword, hash) && user != null;
    }
}
