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

    // 다른 사용자의 지출과 없는 지출을 구분하지 않는다.
    public static ApiException expenseNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "EXPENSE_NOT_FOUND", "지출을 찾을 수 없습니다.", null);
    }

    public static ApiException invalidCursor() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "커서가 올바르지 않습니다.", null);
    }

    // 홈 진입 조건. 둘 다 없으면 펫을 먼저 안내한다.
    public static ApiException petRequired() {
        return new ApiException(HttpStatus.CONFLICT, "PET_REQUIRED", "펫을 먼저 선택해야 합니다.", null);
    }

    public static ApiException budgetRequired() {
        return new ApiException(HttpStatus.CONFLICT, "BUDGET_REQUIRED", "현재 월 예산을 먼저 설정해야 합니다.", null);
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
