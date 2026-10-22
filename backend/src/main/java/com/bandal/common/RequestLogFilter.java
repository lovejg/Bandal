package com.bandal.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

// 요청마다 요청 id를 정하고, 끝나면 "무엇을, 누가, 얼마나 걸려, 어떻게 끝났나"를 로그 한 줄로 남긴다
// 요청 id는 MDC(이 스레드의 로그에 자동으로 붙는 칸)에 넣어서, 이 요청 중에 찍히는 모든 로그가 같은 id를 단다.
// 운영에서는 로그가 JSON으로 나가고(application-prod.yml) MDC 칸도 JSON 칸이 되어 Loki에서 이 id로 묶어 찾는다
// 보안 필터보다 먼저 돌아야 인증 실패(401) 로그에도 id가 붙는다. 그래서 가장 앞에 둔다
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class RequestLogFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";
    // JwtAuthenticationFilter가 토큰에서 사용자를 찾으면 넣는다. 여기서 같이 지운다
    public static final String MDC_USER_ID = "userId";

    // 바깥 Nginx가 만든 id(16진수 32자)를 이어받는다. 아무 값이나 받으면 로그에 남이 만든 글자가 섞이므로 모양을 확인한다
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        MDC.put(MDC_REQUEST_ID, requestId);
        // 화면이나 사용자가 "이 요청이 이상했다"고 알려 줄 때 이 값을 주면 바로 찾을 수 있다
        response.setHeader(HEADER, requestId);

        long startedAt = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            logRequest(request, response, durationMs);
            // 스레드는 다음 요청에 다시 쓰인다. 지우지 않으면 다음 사람 로그에 이 사람 id가 붙는다
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_USER_ID);
        }
    }

    // Prometheus가 10초마다 긁는 /actuator는 줄만 늘려서 남기지 않는다
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    private String resolveRequestId(HttpServletRequest request) {
        String fromHeader = request.getHeader(HEADER);
        if (fromHeader != null && VALID_ID.matcher(fromHeader).matches()) {
            return fromHeader;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    // 칸은 MDC로 넘긴다. 운영의 JSON 로그에서 method, uri, status, durationMs가 각각 칸이 된다
    private void logRequest(HttpServletRequest request, HttpServletResponse response, long durationMs) {
        // 경로 변수 자리를 {groupOrderId}로 묶은 이름. 없으면(없는 주소) 실제 주소를 쓴다
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String uri = pattern != null ? pattern.toString() : request.getRequestURI();
        int status = response.getStatus();

        MDC.put("method", request.getMethod());
        MDC.put("uri", uri);
        MDC.put("status", String.valueOf(status));
        MDC.put("durationMs", String.valueOf(durationMs));
        try {
            // 500은 우리가 예상 못 한 실패라 WARN으로 올린다. 스택은 ApiExceptionHandler가 따로 남긴다
            if (status >= 500) {
                log.warn("{} {} {} {}ms", request.getMethod(), uri, status, durationMs);
            } else {
                log.info("{} {} {} {}ms", request.getMethod(), uri, status, durationMs);
            }
        } finally {
            MDC.remove("method");
            MDC.remove("uri");
            MDC.remove("status");
            MDC.remove("durationMs");
        }
    }
}
