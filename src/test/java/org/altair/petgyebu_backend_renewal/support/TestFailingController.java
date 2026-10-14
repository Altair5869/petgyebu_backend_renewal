package org.altair.petgyebu_backend_renewal.support;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// 테스트 클래스패스에만 있는 처리되지 않은 예외 재현용 엔드포인트.
@RestController
public class TestFailingController {

    public static final String SECRET_MESSAGE = "internal-detail-should-not-leak";

    @GetMapping("/api/v1/test/fail")
    void fail() {
        throw new IllegalStateException(SECRET_MESSAGE);
    }
}
