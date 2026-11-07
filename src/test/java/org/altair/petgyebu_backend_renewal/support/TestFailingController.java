package org.altair.petgyebu_backend_renewal.support;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// 테스트 클래스패스에만 있는 처리되지 않은 예외 재현용 엔드포인트.
@RestController
public class TestFailingController {

    public static final String SECRET_MESSAGE = "internal-detail-should-not-leak";

    // 로그 마스킹 확인용. PostgreSQL 오류 상세처럼 토큰·키 값이 예외 메시지에 들어간 경우.
    public static final String LEAKY_JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI3In0.Qm9ndXMtc2lnbmF0dXJlX3ZhbHVl";
    public static final String LEAKY_KEY_VALUE = "KAKAO, 4815162342";

    @GetMapping("/api/v1/test/fail")
    void fail() {
        throw new IllegalStateException(SECRET_MESSAGE);
    }

    @GetMapping("/api/v1/test/fail-with-secrets")
    void failWithSecrets() {
        throw new IllegalStateException("token " + LEAKY_JWT,
                new RuntimeException("Detail: Key (provider, provider_user_id)=(" + LEAKY_KEY_VALUE + ") already exists."));
    }
}
