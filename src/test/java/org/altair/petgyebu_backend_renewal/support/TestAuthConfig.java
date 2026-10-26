package org.altair.petgyebu_backend_renewal.support;

import java.time.Instant;
import org.altair.petgyebu_backend_renewal.auth.SocialIdentityVerifier;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestAuthConfig {

    public static final Instant START = Instant.parse("2026-10-13T00:00:00Z");

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(START);
    }

    @Bean
    @Primary
    SocialIdentityVerifier fakeSocialIdentityVerifier() {
        return (provider, credential) -> {
            if (credential.startsWith("ok:")) {
                return credential.substring(3);
            }
            if (credential.equals("unavailable")) {
                throw ApiException.socialLoginUnavailable();
            }
            throw ApiException.socialLoginFailed();
        };
    }
}
