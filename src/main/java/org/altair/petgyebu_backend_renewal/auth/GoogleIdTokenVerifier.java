package org.altair.petgyebu_backend_renewal.auth;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

// Google Sign-In이 발급한 ID 토큰을 구글 공개키(JWKS)로 검증한다. aud는 우리 클라이언트 ID 중 하나여야 한다.
class GoogleIdTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleIdTokenVerifier.class);
    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final List<String> clientIds;
    private final NimbusJwtDecoder decoder;

    GoogleIdTokenVerifier(URI jwkSetUri, List<String> clientIds, Clock clock, Duration connectTimeout,
            Duration readTimeout) {
        this.clientIds = List.copyOf(clientIds);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri.toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(requestFactory))
                .build();
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtConfig.notExpired(clock),
                new JwtClaimValidator<String>(JwtClaimNames.ISS, ISSUERS::contains),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.stream().anyMatch(this.clientIds::contains))));
    }

    String verify(String idToken) {
        if (clientIds.isEmpty()) {
            throw ApiException.socialLoginUnavailable();
        }
        try {
            return decoder.decode(idToken).getSubject();
        } catch (BadJwtException e) {
            log.warn("Google ID token rejected: {}", e.getClass().getSimpleName());
            throw ApiException.socialLoginFailed();
        } catch (JwtException e) {
            log.warn("Google ID token check unavailable: {}", e.getClass().getSimpleName());
            throw ApiException.socialLoginUnavailable();
        }
    }
}
