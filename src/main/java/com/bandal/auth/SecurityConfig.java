package com.bandal.auth;

import com.bandal.common.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

// Spring Security 설정
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 쿠키가 아니라 토큰으로 인증하므로 CSRF 공격 대상이 아니다
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 규칙은 위에서부터 차례로 맞춰본다. 먼저 걸리는 규칙이 이긴다.
                // 그래서 좁은 규칙이 넓은 규칙보다 위에 있어야 한다
                .authorizeHttpRequests(auth -> auth
                        // 아래 permitAll보다 위에 있어야 한다. 순서가 바뀌면 누구나 남의 메일로
                        // 인증 메일을 계속 보낼 수 있다
                        .requestMatchers(HttpMethod.POST, "/api/auth/verify/resend").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                        // 가입, 로그인, 토큰 갱신, 인증 링크는 계정이 없거나 미인증이어도 불러야 한다
                        .requestMatchers("/api/auth/**").permitAll()
                        // 조회는 비로그인도 가능하다. 미인증 사용자도 앱 구경은 할 수 있다 (ADR-027)
                        .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        // 쓰기는 메일 인증을 마친 사람만. hasRole("VERIFIED")는 ROLE_VERIFIED를 찾는다
                        .anyRequest().hasRole("VERIFIED"))
                // 우리 필터를 스프링의 로그인 처리 필터 앞에 끼운다
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(handler -> handler
                        // 토큰이 없거나 못 믿을 때
                        .authenticationEntryPoint((request, response, e) ->
                                write(response, HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"))
                        // 로그인은 했지만 권한이 모자랄 때. 지금 403이 나는 경우는 미인증 하나뿐이다.
                        // 권한이 늘어나면 이 메시지를 나눠야 한다
                        .accessDeniedHandler((request, response, e) ->
                                write(response, HttpStatus.FORBIDDEN, "이메일 인증이 필요합니다")));

        return http.build();
    }

    // 시큐리티 필터는 컨트롤러 바깥이라 @RestControllerAdvice가 잡지 못한다.
    // 그래서 실패 응답 모양을 여기서 직접 맞춰준다
    private void write(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(new ErrorResponse(status.value(), message)));
    }

    // 비밀번호 해싱. 느린 해시 + 자동 salt
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
