package org.altair.petgyebu_backend_renewal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ProviderSocialIdentityVerifierTests {

    private FakeProviderServer kakao;
    private ProviderSocialIdentityVerifier verifier;

    @BeforeEach
    void setUp() throws IOException {
        kakao = FakeProviderServer.start();
        verifier = new ProviderSocialIdentityVerifier(
                new KakaoTokenVerifier(kakao.uri(), "123", Duration.ofSeconds(2), Duration.ofSeconds(2)),
                new GoogleIdTokenVerifier(kakao.uri(), List.of(), Clock.systemUTC(), Duration.ofSeconds(2),
                        Duration.ofSeconds(2)));
    }

    @AfterEach
    void stop() {
        kakao.close();
    }

    @Test
    void delegatesKakaoToKakaoVerifier() {
        kakao.respond(200, "{\"id\":4242,\"app_id\":123}");

        assertThat(verifier.verify(Provider.KAKAO, "t")).isEqualTo("4242");
    }

    @Test
    void delegatesGoogleToGoogleVerifier() {
        // client IDs are empty → Google verifier reports unavailable without network
        assertThatThrownBy(() -> verifier.verify(Provider.GOOGLE, "t"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    // 256자리 id는 Long 파싱에서 먼저 실패해 503이 된다. 어떤 경우에도 500이 되지 않음을 보장한다.
    @Test
    void overlongKakaoIdIsUnavailableNot500() {
        String tooLong = "9".repeat(ProviderSocialIdentityVerifier.MAX_PROVIDER_USER_ID_LENGTH + 1);
        kakao.respond(200, "{\"id\":" + tooLong + ",\"app_id\":123}");

        assertThatThrownBy(() -> verifier.verify(Provider.KAKAO, "t")).isInstanceOfSatisfying(ApiException.class,
                e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.code()).isEqualTo("SOCIAL_LOGIN_UNAVAILABLE");
                });
    }

    @Test
    void lengthRuleRejectsNullBlankAndOverlongIds() {
        SocialIdentityVerifier nul = ProviderSocialIdentityVerifier.requireStorable((p, c) -> null);
        SocialIdentityVerifier blank = ProviderSocialIdentityVerifier.requireStorable((p, c) -> " ");
        SocialIdentityVerifier overlong = ProviderSocialIdentityVerifier.requireStorable((p, c) -> "a".repeat(256));
        SocialIdentityVerifier max = ProviderSocialIdentityVerifier.requireStorable((p, c) -> "a".repeat(255));

        assertThatThrownBy(() -> nul.verify(Provider.GOOGLE, "t")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("SOCIAL_LOGIN_FAILED"));
        assertThatThrownBy(() -> blank.verify(Provider.KAKAO, "t")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("SOCIAL_LOGIN_FAILED"));
        assertThatThrownBy(() -> overlong.verify(Provider.GOOGLE, "t")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("SOCIAL_LOGIN_FAILED"));
        assertThat(max.verify(Provider.KAKAO, "t")).hasSize(255);
    }
}
