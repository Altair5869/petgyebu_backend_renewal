package org.altair.petgyebu_backend_renewal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class KakaoTokenVerifierTests {

    private FakeProviderServer kakao;

    @BeforeEach
    void startServer() throws IOException {
        kakao = FakeProviderServer.start();
    }

    @AfterEach
    void stopServer() {
        kakao.close();
    }

    private KakaoTokenVerifier verifier(String appId) {
        return new KakaoTokenVerifier(kakao.uri(), appId, Duration.ofSeconds(2), Duration.ofMillis(500));
    }

    @Test
    void returnsKakaoMemberIdWhenTokenBelongsToOurApp() {
        kakao.respond(200, "{\"id\":4242,\"expires_in\":7199,\"app_id\":123}");

        assertThat(verifier("123").verify("kakao-token")).isEqualTo("4242");
        assertThat(kakao.lastPath()).isEqualTo("/v1/user/access_token_info");
        assertThat(kakao.lastAuthorization()).isEqualTo("Bearer kakao-token");
    }

    @Test
    void rejectsTokenIssuedForAnotherApp() {
        kakao.respond(200, "{\"id\":4242,\"expires_in\":7199,\"app_id\":999}");

        assertFailed(() -> verifier("123").verify("kakao-token"));
    }

    @Test
    void rejectsInvalidOrExpiredToken() {
        kakao.respond(401, "{\"msg\":\"this access token does not exist\",\"code\":-401}");
        assertFailed(() -> verifier("123").verify("bad"));

        kakao.respond(400, "{\"msg\":\"wrong format\",\"code\":-2}");
        assertFailed(() -> verifier("123").verify("bad"));
    }

    @Test
    void providerFailuresAreUnavailable() {
        kakao.respond(500, "{}");
        assertUnavailable(() -> verifier("123").verify("t"));

        kakao.respond(429, "{}");
        assertUnavailable(() -> verifier("123").verify("t"));

        kakao.respond(200, "not json");
        assertUnavailable(() -> verifier("123").verify("t"));

        kakao.respond(200, "{\"expires_in\":7199,\"app_id\":123}");
        assertUnavailable(() -> verifier("123").verify("t"));

        kakao.respond(200, "{\"id\":4242,\"expires_in\":7199}");
        assertUnavailable(() -> verifier("123").verify("t"));
    }

    @Test
    void slowProviderTimesOutAsUnavailable() {
        kakao.respond(200, "{\"id\":4242,\"app_id\":123}");
        kakao.delay(Duration.ofSeconds(2));

        assertUnavailable(() -> verifier("123").verify("t"));
    }

    @Test
    void unconfiguredAppIdIsUnavailableWithoutCallingKakao() {
        assertUnavailable(() -> verifier("").verify("t"));
        assertThat(kakao.requestCount()).isZero();
    }

    @Test
    void tokenWithControlCharactersIsRejectedWithoutCallingKakao() {
        assertFailed(() -> verifier("123").verify("abc\ndef"));
        assertFailed(() -> verifier("123").verify("abc\rdef"));
        assertThat(kakao.requestCount()).isZero();
    }

    private static void assertFailed(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(e.code()).isEqualTo("SOCIAL_LOGIN_FAILED");
        });
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.code()).isEqualTo("SOCIAL_LOGIN_UNAVAILABLE");
        });
    }
}
