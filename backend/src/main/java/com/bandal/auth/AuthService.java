package com.bandal.auth;

import com.bandal.auth.dto.LoginRequest;
import com.bandal.auth.dto.SignUpRequest;
import com.bandal.auth.dto.SignUpResponse;
import com.bandal.auth.dto.TokenResponse;
import com.bandal.auth.dto.UserResponse;
import com.bandal.common.UnauthorizedException;
import com.bandal.outbox.OutboxMessage;
import com.bandal.outbox.OutboxMessageRepository;
import com.bandal.outbox.OutboxType;
import com.bandal.university.University;
import com.bandal.university.UniversityRepository;
import com.bandal.user.User;
import com.bandal.user.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UniversityRepository universityRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final OutboxMessageRepository outboxMessageRepository;

    // 계정 열거 방지용 가짜 해시
    // 사용자를 못 찾았을 때도 이걸로 대조를 한 번 돌려서 응답 시간을 맞춘다
    // 없는 이메일만 빨리 실패하면 시간만 재봐도 가입 여부를 알 수 있다
    private String dummyHash;

    @PostConstruct
    void initDummyHash() {
        dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    // 가입 성공이든 이미 있는 주소든 이 문장 하나로 답한다.
    // 두 응답이 한 글자라도 다르면 계정 존재 여부가 새므로 상수로 묶어둔다
    private static final String SIGN_UP_MESSAGE = "가입 확인 메일을 보냈습니다. 메일함을 확인해주세요";

    @Transactional
    public SignUpResponse signUp(SignUpRequest request) {
        String email = normalizeEmail(request.email());
        String password = request.password();
        String nickname = request.nickname();
        String domain = email.substring(email.indexOf('@') + 1);

        List<University> campuses = universityRepository.findAllByEmailDomainOrderById(domain);
        if(campuses.isEmpty()) {
            throw new IllegalArgumentException("지원하지 않는 학교 이메일입니다");
        }
        University university = campuses.stream()
            .filter(campus -> campus.getId().equals(request.universityId()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("이메일과 고른 학교가 맞지 않습니다"));

        // 닉네임은 방 목록에 그대로 보이는 공개 정보라 감출 게 없다. 그대로 알려준다
        if(userRepository.existsByNickname(nickname)) {
            throw new IllegalStateException("이미 쓰이고 있는 닉네임입니다");
        }

        // 있는 주소든 아니든 해시를 먼저 만든다. 새 주소만 해시(약 50ms)하면 있는 주소가 5ms에 돌아와 응답 시간으로 가입 여부가 샌다
        // 있는 주소면 이 해시는 쓰지 않고 버린다
        String hashed = passwordEncoder.encode(password);

        // 이미 있는 주소(이메일)인 케이스
        User existing = userRepository.findByEmail(email).orElse(null);
        if(existing != null) {
            outboxMessageRepository.save(OutboxMessage.mail(OutboxType.ALREADY_REGISTERED_MAIL,
                existing.getId(), Instant.now()));
            return new SignUpResponse(SIGN_UP_MESSAGE);
        }

        User user = new User(university, email, hashed, nickname);
        userRepository.save(user);

        outboxMessageRepository.save(OutboxMessage.mail(OutboxType.VERIFICATION_MAIL, user.getId(), Instant.now()));

        return new SignUpResponse(SIGN_UP_MESSAGE);
    }

    // 내 정보. 프론트가 "나 인증됐나"를 확인할 때 쓴다
    // open-in-view를 꺼놨으므로 DTO 변환까지 트랜잭션 안에서 끝내야 한다
    @Transactional(readOnly = true)
    public UserResponse me(Long userId) {
        return UserResponse.of(userRepository.findByIdOrThrow(userId));
    }

    // 로그인. 실패 이유는 구분하지 않는다
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(normalizeEmail(request.email())).orElse(null);

        if (!matchesPassword(user, request.password())) {
            throw new UnauthorizedException("이메일 또는 비밀번호가 올바르지 않습니다");
        }

        return new TokenResponse(jwtProvider.createAccessToken(user.getId()),
                refreshTokenStore.issue(user.getId()));
    }

    // 액세스 토큰이 만료됐을 때 새로 받는다. 다시 로그인하지 않아도 된다
    public TokenResponse refresh(String refreshToken) {
        Long userId = refreshTokenStore.consume(refreshToken); // 꺼내면서 지운다(revoke를 따로 하지 않음)
        if(userId == null) {
            throw new UnauthorizedException("다시 로그인해주세요");
        }
        // 리프레시 토큰도 새로 준다. 옛 토큰은 위에서 지워져 한 번만 쓸 수 있다
        return new TokenResponse(jwtProvider.createAccessToken(userId), refreshTokenStore.issue(userId));
    }

    // 로그아웃. 리프레시 토큰을 지우면 더는 갱신할 수 없다
    // 이미 발급된 액세스 토큰은 만료(15분)까지 살아 있다
    // 없는 토큰이어도 에러 없이 끝낸다(토큰이 있었는지 알려 주지 않는다)
    public void logout(String refreshToken) {
        refreshTokenStore.revoke(refreshToken);
    }

    // 이메일은 대소문자를 가리지 않는다. Kim@과 kim@이 다른 계정이 되면 같은 사람이 두 번 가입할 수 있고,
    // 가입할 때와 다르게 쳐서 로그인이 막힌다. 저장과 조회 모두 소문자로 맞춘다
    // Locale.ROOT: 터키어 설정에서는 I가 점 없는 i로 바뀐다. 언어 설정과 상관없이 같은 결과를 낸다
    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    // 사용자가 없어도 더미 해시로 대조를 한 번 돌린다 (위 dummyHash 참고)
    private boolean matchesPassword(User user, String rawPassword) {
        String hash = (user == null) ? dummyHash : user.getPassword();
        // 순서를 바꾸면 안 된다. user != null을 앞에 두면 단축 평가 때문에 없는 이메일일 때 대조를 건너뛰어서 위의 방어가 사라진다
        return passwordEncoder.matches(rawPassword, hash) && user != null;
    }
}
