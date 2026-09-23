package com.bandal.auth;

import com.bandal.user.User;
import com.bandal.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

// 요청마다 Authorization 헤더의 토큰을 확인해서 "지금 누가 부르고 있는지"를 정한다.
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    // 가입은 했지만 메일 인증을 아직 안 한 사람
    public static final String ROLE_USER = "ROLE_USER";
    // 메일 인증까지 마친 사람. 쓰기는 이쪽만 할 수 있다 (ADR-027)
    public static final String ROLE_VERIFIED = "ROLE_VERIFIED";

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        Long userId = resolveUserId(request);
        // 토큰은 멀쩡한데 그 사용자가 없을 수 있다. 탈퇴한 계정의 토큰이 아직 안 만료된 경우다.
        // 그때는 인증을 아예 하지 않아서 비로그인(401)으로 떨어뜨린다.
        // "이메일 인증이 필요합니다"(403)는 인증할 계정조차 없는 사람에게 할 말이 아니다
        User user = (userId == null) ? null : userRepository.findById(userId).orElse(null);

        if (user != null) {
            // principal을 사용자 id로 둔다. 컨트롤러에서 @AuthenticationPrincipal로 받는다
            var authentication = new UsernamePasswordAuthenticationToken(user.getId(), null, authorities(user));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        // 토큰이 없거나 못 믿을 토큰이면 그냥 비로그인 상태로 통과시킨다.
        // 막는 건 여기가 아니라 SecurityConfig의 authorizeHttpRequests가 한다
        filterChain.doFilter(request, response);
    }

    // 이 사람이 무엇을 할 수 있는지를 권한 목록으로 만든다.
    // 인증 여부를 토큰에 담지 않고 매번 DB에서 읽는 이유는 ADR-027에 있다.
    // 토큰에 담으면 메일 인증을 마쳐도 토큰이 만료될 때까지 계속 막힌다
    private List<GrantedAuthority> authorities(User user) {
        if (user.isEmailVerified()) {
            return List.of(new SimpleGrantedAuthority(ROLE_VERIFIED));
        }
        else {
            return List.of(new SimpleGrantedAuthority(ROLE_USER));
        }
    }

    private Long resolveUserId(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            return null;
        }
        return jwtProvider.parseUserId(header.substring(PREFIX.length()));
    }
}
