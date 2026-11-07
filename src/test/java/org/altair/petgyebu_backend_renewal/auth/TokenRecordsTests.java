package org.altair.petgyebu_backend_renewal.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenRecordsTests {

    static final String CREDENTIAL = "kakao-sdk-access-token-value";
    static final String ACCESS = "access-token-value";
    static final String REFRESH = "7.refresh-token-secret-value";

    @Test
    void toStringOmitsTokenValues() {
        assertThat(new AuthController.LoginRequest(CREDENTIAL).toString()).doesNotContain(CREDENTIAL);
        assertThat(new AuthController.RefreshTokenRequest(REFRESH).toString()).doesNotContain(REFRESH);
        assertThat(new AuthController.TokenResponse(ACCESS, REFRESH, 900).toString())
                .doesNotContain(ACCESS).doesNotContain(REFRESH).contains("accessExpiresInSeconds=900");
        assertThat(new AuthService.Tokens(ACCESS, REFRESH).toString()).doesNotContain(ACCESS).doesNotContain(REFRESH);
    }
}
