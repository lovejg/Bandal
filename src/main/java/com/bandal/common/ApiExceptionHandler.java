package com.bandal.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// 모든 컨트롤러의 예외를 여기서 한 번에 상태 코드로 바꾼다 (ADR-023)
// 어떤 실패든 응답 모양은 ErrorResponse 하나로 통일한다
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    // 없는 자원을 가리켰다
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e.getMessage());
    }

    // 값 자체가 틀렸다. 음수 가격, 빈 메뉴 이름 같은 것
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    // 값은 멀쩡한데 지금 상태에서는 할 수 없다. 마감된 방에 메뉴 담기 같은 것
    // 서버가 고장난 게 아니라서 500이 아니라 409다
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
        return build(HttpStatus.CONFLICT, e.getMessage());
    }

    // @Valid가 걸러낸 요청. 어느 필드가 왜 틀렸는지 모아서 알려준다
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + ", " + b)
                .orElse("잘못된 요청입니다");
        return build(HttpStatus.BAD_REQUEST, message);
    }

    // 헤더가 빠졌다. 기본 메시지는 영어라 우리가 다시 쓴다
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        return build(HttpStatus.BAD_REQUEST, e.getHeaderName() + " 헤더가 필요합니다");
    }

    // 경로 변수나 쿼리 값의 타입이 안 맞는다. /api/group-orders/abc 같은 것.
    // 이 예외는 스프링의 ErrorResponse가 아니라서 아래 catch-all에 두면 500이 된다
    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(TypeMismatchException e) {
        return build(HttpStatus.BAD_REQUEST, "요청 값의 형식이 올바르지 않습니다: " + e.getValue());
    }

    // 본문이 비었거나 JSON이 깨졌다
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "요청 본문을 읽을 수 없습니다");
    }

    // 위에서 안 걸린 나머지.
    // 스프링이 던지는 웹 예외(없는 주소, 안 되는 메서드, 경로 변수 타입 불일치 등)는
    // 자기 상태 코드를 알고 있으므로 그 코드를 그대로 쓰고 모양만 우리 것으로 맞춘다.
    // 그 밖의 예외는 우리가 예상 못 한 버그라서 500으로 나가고, 스택 트레이스를 로그에 남긴다.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleEtc(Exception e) {
        // 이름이 같은 게 둘이라 헷갈린다. 아래는 스프링의 인터페이스, 우리 것은 com.bandal.common.ErrorResponse
        if (e instanceof org.springframework.web.ErrorResponse springError) {
            HttpStatusCode status = springError.getStatusCode();
            return ResponseEntity.status(status)
                    .body(new ErrorResponse(status.value(), e.getMessage()));
        }

        log.error("처리하지 못한 예외", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "서버에서 문제가 생겼습니다");
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(status.value(), message));
    }
}
