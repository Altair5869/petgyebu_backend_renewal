package org.altair.petgyebu_backend_renewal.auth;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSetBasedJWKSource;
import com.nimbusds.jose.jwk.source.JWKSetCacheRefreshEvaluator;
import com.nimbusds.jose.jwk.source.JWKSetSource;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import java.net.MalformedURLException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

// Google Sign-In이 발급한 ID 토큰을 구글 공개키(JWKS)로 검증한다. aud는 우리 클라이언트 ID 중 하나여야 한다.
class GoogleIdTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleIdTokenVerifier.class);
    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final List<String> clientIds;
    private final NimbusJwtDecoder decoder;

    GoogleIdTokenVerifier(URI jwkSetUri, List<String> clientIds, Clock clock, Duration connectTimeout,
            Duration readTimeout) {
        this.clientIds = List.copyOf(clientIds);
        this.decoder = NimbusJwtDecoder.withJwkSource(googleKeys(jwkSetUri, connectTimeout, readTimeout))
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtConfig.notExpired(clock),
                new JwtClaimValidator<String>(JwtClaimNames.ISS, ISSUERS::contains),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.stream().anyMatch(this.clientIds::contains))));
    }

    // Nimbus 기본 캐시(5분)와 재조회 제한(30초에 한 번)을 쓴다. 모르는 kid가 오면 한 번 다시 가져오고(키 교체 대응),
    // 제한에 걸리면 방금 받은 키 목록에 없는 kid이므로 일치하는 키 없음(401)으로 본다.
    // 캐시 자체를 못 채운 경우(구글 장애)는 그대로 예외를 던져 503이 된다.
    private static JWKSource<SecurityContext> googleKeys(URI jwkSetUri, Duration connectTimeout, Duration readTimeout) {
        JWKSetSource<SecurityContext> keySets;
        try {
            // build()는 장애 조치(failover)를 설정하지 않으면 JWKSetBasedJWKSource를 돌려준다.
            keySets = ((JWKSetBasedJWKSource<SecurityContext>) JWKSourceBuilder.<SecurityContext>create(
                    jwkSetUri.toURL(),
                    new DefaultResourceRetriever((int) connectTimeout.toMillis(), (int) readTimeout.toMillis(),
                            JWKSourceBuilder.DEFAULT_HTTP_SIZE_LIMIT))
                    .build()).getJWKSetSource();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid Google JWKS URI", e);
        }
        return (selector, context) -> {
            long now = System.currentTimeMillis();
            JWKSet cached = keySets.getJWKSet(JWKSetCacheRefreshEvaluator.noRefresh(), now, context);
            List<JWK> keys = selector.select(cached);
            if (!keys.isEmpty()) {
                return keys;
            }
            try {
                return selector.select(
                        keySets.getJWKSet(JWKSetCacheRefreshEvaluator.referenceComparison(cached), now, context));
            } catch (RateLimitReachedException e) {
                return List.of();
            }
        };
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
