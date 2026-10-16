package org.altair.petgyebu_backend_renewal.auth;

import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SocialLoginProperties.class)
public class SocialLoginConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    SocialIdentityVerifier socialIdentityVerifier(SocialLoginProperties properties, Clock clock) {
        return new ProviderSocialIdentityVerifier(
                new KakaoTokenVerifier(properties.kakao().baseUrl(), properties.kakao().appId(),
                        CONNECT_TIMEOUT, READ_TIMEOUT),
                new GoogleIdTokenVerifier(properties.google().jwkSetUri(), properties.google().clientIds(), clock,
                        CONNECT_TIMEOUT, READ_TIMEOUT));
    }
}
