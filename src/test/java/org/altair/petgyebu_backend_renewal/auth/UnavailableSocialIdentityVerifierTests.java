package org.altair.petgyebu_backend_renewal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class UnavailableSocialIdentityVerifierTests {

    @Test
    void rejectsEveryLoginUntilProvidersAreIntegrated() {
        assertThatThrownBy(() -> new UnavailableSocialIdentityVerifier().verify(Provider.KAKAO, "anything"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.code()).isEqualTo("SOCIAL_LOGIN_UNAVAILABLE");
                });
    }
}
