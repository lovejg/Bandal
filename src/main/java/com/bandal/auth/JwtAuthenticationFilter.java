package com.bandal.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

// 요청마다 Authorization 헤더의 토큰을 확인해서 "지금 누가 부르고 있는지"를 정한다.
// X-User-Id와 다른 점은, 이 값은 클라이언트가 마음대로 못 정한다는 것이다 (ADR-024)
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        Long userId = resolveUserId(request);

        if (userId != null) {
            // principal을 사용자 id로 둔다. 컨트롤러에서 @AuthenticationPrincipal로 받는다
            var authentication = new UsernamePasswordAuthenticationToken(userId, null, List.of());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        // 토큰이 없거나 못 믿을 토큰이면 그냥 비로그인 상태로 통과시킨다.
        // 막는 건 여기가 아니라 SecurityConfig의 authorizeHttpRequests가 한다
        filterChain.doFilter(request, response);
    }

    private Long resolveUserId(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            return null;
        }
        return jwtProvider.parseUserId(header.substring(PREFIX.length()));
    }
}
