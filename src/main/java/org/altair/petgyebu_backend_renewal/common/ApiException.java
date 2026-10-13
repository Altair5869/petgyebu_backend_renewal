package org.altair.petgyebu_backend_renewal.common;

import java.util.List;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ApiError.FieldError> fields;

    public ApiException(HttpStatus status, String code, String message, List<ApiError.FieldError> fields) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = fields;
    }

    public static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다.", null);
    }

    public static ApiException socialLoginFailed() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "SOCIAL_LOGIN_FAILED", "소셜 로그인을 확인할 수 없습니다.", null);
    }

    public static ApiException socialLoginUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SOCIAL_LOGIN_UNAVAILABLE",
                "소셜 로그인을 사용할 수 없습니다.", null);
    }

    public static ApiException invalidRequest(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값이 올바르지 않습니다.",
                List.of(new ApiError.FieldError(field, message)));
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<ApiError.FieldError> fields() {
        return fields;
    }
}
