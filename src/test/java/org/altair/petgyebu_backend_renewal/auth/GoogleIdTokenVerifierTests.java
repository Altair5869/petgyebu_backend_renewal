package org.altair.petgyebu_backend_renewal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GoogleIdTokenVerifierTests {

    private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
    private static final String CLIENT_ID = "ios.apps.googleusercontent.com";

    private FakeProviderServer google;
    private RSAKey key;

    @BeforeEach
    void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        google = FakeProviderServer.start();
        google.respond(200, new JWKSet(key.toPublicJWK()).toString());
    }

    @AfterEach
    void stopServer() {
        google.close();
    }

    private GoogleIdTokenVerifier verifier(List<String> clientIds) {
        return new GoogleIdTokenVerifier(google.uri().resolve("/oauth2/v3/certs"), clientIds,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(2), Duration.ofMillis(500));
    }

    private String token(RSAKey signingKey, String iss, List<String> aud, Instant exp) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(iss)
                .audience(aud)
                .subject("google-sub-1")
                .issueTime(Date.from(NOW.minusSeconds(60)))
                .expirationTime(Date.from(exp))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    @Test
    void returnsSubjectForValidToken() throws Exception {
        String idToken = token(key, "https://accounts.google.com", List.of(CLIENT_ID), NOW.plusSeconds(3600));

        assertThat(verifier(List.of(CLIENT_ID)).verify(idToken)).isEqualTo("google-sub-1");
        assertThat(verifier(List.of(CLIENT_ID)).verify(
                token(key, "accounts.google.com", List.of(CLIENT_ID), NOW.plusSeconds(3600)))).isEqualTo("google-sub-1");
    }

    @Test
    void acceptsWhenOneOfSeveralAudiencesIsOurs() throws Exception {
        String idToken = token(key, "https://accounts.google.com", List.of("other-client", CLIENT_ID),
                NOW.plusSeconds(3600));

        assertThat(verifier(List.of("web-client", CLIENT_ID)).verify(idToken)).isEqualTo("google-sub-1");
    }

    @Test
    void rejectsTokensThatFailValidation() throws Exception {
        RSAKey otherKey = new RSAKeyGenerator(2048).keyID("k1").generate();
        GoogleIdTokenVerifier verifier = verifier(List.of(CLIENT_ID));

        assertFailed(() -> verifier.verify(
                token(otherKey, "https://accounts.google.com", List.of(CLIENT_ID), NOW.plusSeconds(3600))));
        assertFailed(() -> verifier.verify(
                token(key, "https://accounts.google.com", List.of("someone-else"), NOW.plusSeconds(3600))));
        assertFailed(() -> verifier.verify(
                token(key, "https://evil.example.com", List.of(CLIENT_ID), NOW.plusSeconds(3600))));
        assertFailed(() -> verifier.verify(
                token(key, "https://accounts.google.com", List.of(CLIENT_ID), NOW)));
        assertFailed(() -> verifier.verify("not-a-jwt"));
    }

    @Test
    void unknownKeyIdsDoNotRefetchKeysEveryTime() throws Exception {
        GoogleIdTokenVerifier verifier = verifier(List.of(CLIENT_ID));
        verifier.verify(token(key, "https://accounts.google.com", List.of(CLIENT_ID), NOW.plusSeconds(3600)));
        int fetchesAfterWarmUp = google.requestCount();

        for (int i = 0; i < 10; i++) {
            RSAKey unknownKey = new RSAKeyGenerator(2048).keyID("unknown-" + i).generate();
            assertFailed(() -> verifier.verify(
                    token(unknownKey, "https://accounts.google.com", List.of(CLIENT_ID), NOW.plusSeconds(3600))));
        }

        assertThat(google.requestCount() - fetchesAfterWarmUp).isLessThanOrEqualTo(1);
    }

    @Test
    void acceptsTokenSignedWithRotatedKey() throws Exception {
        GoogleIdTokenVerifier verifier = verifier(List.of(CLIENT_ID));
        verifier.verify(token(key, "https://accounts.google.com", List.of(CLIENT_ID), NOW.plusSeconds(3600)));
        RSAKey rotatedKey = new RSAKeyGenerator(2048).keyID("k2").generate();
        google.respond(200, new JWKSet(List.of(key.toPublicJWK(), rotatedKey.toPublicJWK())).toString());

        assertThat(verifier.verify(
                token(rotatedKey, "https://accounts.google.com", List.of(CLIENT_ID), NOW.plusSeconds(3600))))
                .isEqualTo("google-sub-1");
    }

    @Test
    void jwksOutageIsUnavailable() throws Exception {
        String idToken = token(key, "https://accounts.google.com", List.of(CLIENT_ID), NOW.plusSeconds(3600));

        google.respond(500, "{}");
        assertUnavailable(() -> verifier(List.of(CLIENT_ID)).verify(idToken));

        google.respond(200, "<html>oops</html>");
        assertUnavailable(() -> verifier(List.of(CLIENT_ID)).verify(idToken));
    }

    @Test
    void repeatedJwksOutageStaysUnavailableWhenRefetchIsRateLimited() throws Exception {
        String idToken = token(key, "https://accounts.google.com", List.of(CLIENT_ID), NOW.plusSeconds(3600));
        GoogleIdTokenVerifier verifier = verifier(List.of(CLIENT_ID));
        google.respond(500, "{}");

        for (int i = 0; i < 5; i++) {
            assertUnavailable(() -> verifier.verify(idToken));
        }
        assertThat(google.requestCount()).isLessThanOrEqualTo(2);
    }

    @Test
    void unconfiguredClientIdsAreUnavailableWithoutFetchingKeys() throws Exception {
        String idToken = token(key, "https://accounts.google.com", List.of(CLIENT_ID), NOW.plusSeconds(3600));

        assertUnavailable(() -> verifier(List.of()).verify(idToken));
        assertThat(google.requestCount()).isZero();
    }

    private static void assertFailed(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(e.code()).isEqualTo("SOCIAL_LOGIN_FAILED");
        });
    }

    private static void assertUnavailable(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.code()).isEqualTo("SOCIAL_LOGIN_UNAVAILABLE");
        });
    }
}
