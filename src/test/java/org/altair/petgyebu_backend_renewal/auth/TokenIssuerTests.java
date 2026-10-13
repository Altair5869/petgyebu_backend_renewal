package org.altair.petgyebu_backend_renewal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.altair.petgyebu_backend_renewal.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class TokenIssuerTests {

    private static final Instant START = Instant.parse("2026-10-13T00:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final JwtProperties properties = new JwtProperties("a".repeat(32));
    private final JwtConfig config = new JwtConfig();
    private final JwtDecoder decoder = config.jwtDecoder(properties, clock);
    private final TokenIssuer issuer = new TokenIssuer(config.jwtEncoder(properties), clock);

    @Test
    void accessTokenCarriesUserSessionAndFifteenMinuteExpiry() {
        UUID sessionId = UUID.randomUUID();

        Jwt jwt = decoder.decode(issuer.issueAccessToken(42L, sessionId));

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(sessionId.toString());
        assertThat(jwt.getExpiresAt()).isEqualTo(START.plus(Duration.ofMinutes(15)));
        assertThat(TokenIssuer.ACCESS_TOKEN_TTL.toSeconds()).isEqualTo(900);
    }

    @Test
    void accessTokenIsValidUntilJustBeforeFifteenMinutes() {
        String token = issuer.issueAccessToken(1L, UUID.randomUUID());

        clock.advance(Duration.ofMinutes(15).minusSeconds(1));
        assertThat(decoder.decode(token).getSubject()).isEqualTo("1");

        clock.advance(Duration.ofSeconds(1));
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        JwtProperties other = new JwtProperties("b".repeat(32));
        String foreign = new TokenIssuer(config.jwtEncoder(other), clock).issueAccessToken(1L, UUID.randomUUID());

        assertThatThrownBy(() -> decoder.decode(foreign)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsUnsignedToken() {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        long exp = START.plus(Duration.ofMinutes(15)).getEpochSecond();
        String payload = b64.encodeToString(("{\"sub\":\"1\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> decoder.decode(header + "." + payload + "."))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void refreshTokenEmbedsUserIdAndRandomSecret() {
        String first = issuer.newRefreshToken(42L);
        String second = issuer.newRefreshToken(42L);

        assertThat(first).matches("42\\.[A-Za-z0-9_-]{43}");
        assertThat(first).isNotEqualTo(second);
        assertThat(issuer.refreshTokenUserId(first)).contains(42L);
    }

    @Test
    void rejectsMalformedRefreshTokens() {
        String jwt = issuer.issueAccessToken(1L, UUID.randomUUID());

        for (String bad : new String[] {
                "", "garbage", "1.", ".secret", "abc.secret", "-1.secret", "0.secret",
                "99999999999999999999.secret", jwt}) {
            assertThat(issuer.refreshTokenUserId(bad)).as(bad).isEmpty();
        }
        assertThat(issuer.refreshTokenUserId(null)).isEmpty();
    }

    @Test
    void hashIsDeterministicSha256Hex() {
        String token = issuer.newRefreshToken(7L);

        assertThat(issuer.hash(token)).matches("[0-9a-f]{64}");
        assertThat(issuer.hash(token)).isEqualTo(issuer.hash(token));
        assertThat(issuer.hash(token)).isNotEqualTo(token);
        assertThat(issuer.hash(issuer.newRefreshToken(7L))).isNotEqualTo(issuer.hash(token));
    }
}
