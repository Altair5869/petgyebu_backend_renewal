package org.altair.petgyebu_backend_renewal.support;

import java.util.Map;
import org.altair.petgyebu_backend_renewal.auth.AuthUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// 테스트 클래스패스에만 있는 인증 확인용 엔드포인트.
@RestController
public class TestMeController {

    @GetMapping("/api/v1/test/me")
    Map<String, Long> me(@AuthenticationPrincipal AuthUser user) {
        return Map.of("userId", user.userId());
    }
}
