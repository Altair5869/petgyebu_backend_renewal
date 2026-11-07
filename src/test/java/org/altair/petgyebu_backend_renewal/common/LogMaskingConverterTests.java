package org.altair.petgyebu_backend_renewal.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class LogMaskingConverterTests {

    static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI3In0.Qm9ndXMtc2lnbmF0dXJlX3ZhbHVl";
    static final String REFRESH_TOKEN = "7.AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-AbCde";

    @Test
    void masksJwtAnywhereInText() {
        assertThat(LogMaskingConverter.mask("token=" + JWT + " end")).isEqualTo("token=*** end");
    }

    @Test
    void masksBearerCredential() {
        assertThat(LogMaskingConverter.mask("Authorization: Bearer abc.def-123/x=")).isEqualTo("Authorization: Bearer ***");
        assertThat(LogMaskingConverter.mask("authorization: bearer opaque")).isEqualTo("authorization: bearer ***");
    }

    @Test
    void masksRefreshToken() {
        assertThat(REFRESH_TOKEN.substring(2)).hasSize(43);
        assertThat(LogMaskingConverter.mask("refresh " + REFRESH_TOKEN + ".")).isEqualTo("refresh ***.");
    }

    @Test
    void masksPostgresKeyValues() {
        assertThat(LogMaskingConverter.mask(
                "Detail: Key (provider, provider_user_id)=(KAKAO, 4815162342) already exists."))
                .isEqualTo("Detail: Key (provider, provider_user_id)=(***) already exists.");
        assertThat(LogMaskingConverter.mask(
                "Detail: Key (user_id)=(42) is not present in table \"users\"."))
                .isEqualTo("Detail: Key (user_id)=(***) is not present in table \"users\".");
        assertThat(LogMaskingConverter.mask(
                "Detail: Key (id)=(42) is still referenced from table \"expenses\"."))
                .isEqualTo("Detail: Key (id)=(***) is still referenced from table \"expenses\".");
    }

    @Test
    void masksPostgresFailingRow() {
        assertThat(LogMaskingConverter.mask("Detail: Failing row contains (1, KAKAO, 4815162342, null).\n  at x"))
                .isEqualTo("Detail: Failing row contains ***\n  at x");
    }

    @Test
    void leavesOrdinaryTextUnchanged() {
        String text = "GET /api/v1/expenses/12 amount=3.5 session=0b6f3c2e-6a4d-4f6e-9a1b-2c3d4e5f6a7b"
                + " at org.altair.Foo.bar(Foo.java:12)";

        assertThat(LogMaskingConverter.mask(text)).isEqualTo(text);
    }

    // 로그는 요청 스레드에서 동기로 출력되므로, 입력값이 예외 메시지에 실린 긴 줄도 빨리 끝나야 한다.
    @Test
    void hostileLongLinesFinishQuickly() {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            LogMaskingConverter.mask("Key (a)=(".repeat(20_000));
            LogMaskingConverter.mask("eyJ".repeat(60_000));
            LogMaskingConverter.mask("Bearer ".repeat(30_000));
            LogMaskingConverter.mask("1.".repeat(90_000));
        });
    }
}
