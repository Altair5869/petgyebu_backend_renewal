package org.altair.petgyebu_backend_renewal.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserTests {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-11-06T09:00:00+09:00");

    @Test
    void toStringOmitsProviderIdentifierAndSessionSecrets() {
        String providerUserId = "kakao-subject-4815162342";
        UUID sessionId = UUID.fromString("0b6f3c2e-6a4d-4f6e-9a1b-2c3d4e5f6a7b");
        String refreshTokenHash = "c0ffee00deadbeefc0ffee00deadbeefc0ffee00deadbeefc0ffee00deadbeef";
        User user = new User(7L, "KAKAO", providerUserId, "CAT", T0, T0, sessionId, refreshTokenHash, T0);

        String text = user.toString();

        assertThat(text)
                .doesNotContain(providerUserId)
                .doesNotContain(sessionId.toString())
                .doesNotContain(refreshTokenHash)
                .contains("id=7", "provider=KAKAO", "petType=CAT");
    }
}
