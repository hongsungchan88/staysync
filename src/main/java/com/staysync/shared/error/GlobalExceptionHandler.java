package com.staysync.shared.error;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 예외를 일관된 JSON 형태로 변환한다. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomain(DomainException e) {
        log.warn("도메인 규칙 위반: code={} message={}", e.code(), e.getMessage());
        return ResponseEntity.status(e.status())
                .body(ApiError.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::describe)
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiError.of("VALIDATION_FAILED", "입력값이 올바르지 않습니다.", details));
    }

    /**
     * 요청 본문을 읽지 못했다.
     *
     * <p>깨진 JSON, 잘못된 인코딩, 맞지 않는 타입이 여기로 온다. <b>클라이언트 오류이므로
     * 400 이다.</b> 이 핸들러가 없으면 아래 {@code Exception} 폴백으로 떨어져 500 이
     * 나가고, 보낸 쪽은 서버가 고장 난 줄 안다.
     *
     * <p>사유를 응답에 담지 않는다. 파서 예외 메시지에는 본문 일부와 내부 클래스 이름이
     * 섞여 나온다. 자세한 내용은 로그에만 남긴다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("요청 본문을 읽지 못했다: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiError.of("MALFORMED_REQUEST", "요청 본문을 읽을 수 없습니다."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("처리하지 못한 예외", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "서버 오류가 발생했습니다."));
    }

    private static String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
