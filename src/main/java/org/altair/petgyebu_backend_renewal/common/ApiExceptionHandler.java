package org.altair.petgyebu_backend_renewal.common;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handle(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST", "요청 본문을 읽을 수 없습니다.", null));
    }
}
