package org.altair.petgyebu_backend_renewal.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handle(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.code(), e.getMessage(), e.fields()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handle(MethodArgumentNotValidException e) {
        List<ApiError.FieldError> fields = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", "요청 값이 올바르지 않습니다.", fields));
    }

    // 경로 변수·쿼리 파라미터의 타입 변환 실패 (예: 숫자가 아닌 지출 ID나 limit)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handle(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "요청 값이 올바르지 않습니다.",
                List.of(new ApiError.FieldError(e.getName(), "형식이 올바르지 않습니다."))));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handle(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST", "요청 본문을 읽을 수 없습니다.", null));
    }

    // 응답 헤더(415의 Accept, 405의 Allow)는 Spring이 채운 값을 그대로 둔다.
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> handle(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).headers(e.getHeaders())
                .body(new ApiError("UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 요청 형식입니다.", null));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handle(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(e.getHeaders())
                .body(new ApiError("METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식입니다.", null));
    }

    // 매핑되지 않은 경로. 인증되지 않은 요청은 보안 필터에서 먼저 401이 된다.
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handle(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", "요청한 경로를 찾을 수 없습니다.", null));
    }

    // 처리되지 않은 예외는 내용을 숨기고 서버 로그에만 남긴다.
    // 위에서 다루지 않은 Spring MVC 4xx 예외(ErrorResponse)는 다시 던져 기본 처리(상태 코드)를 유지한다.
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handle(Exception e, HttpServletRequest request) throws Exception {
        if (e instanceof ErrorResponse) {
            throw e;
        }
        log.error("Unhandled exception: {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "일시적인 오류가 발생했습니다.", null));
    }
}
