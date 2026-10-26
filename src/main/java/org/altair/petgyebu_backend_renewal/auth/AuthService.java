package org.altair.petgyebu_backend_renewal.auth;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.altair.petgyebu_backend_renewal.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {

    public record Tokens(String accessToken, String refreshToken) {
    }

    private final SocialIdentityVerifier verifier;
    private final UserRepository users;
    private final TokenIssuer tokens;
    private final Clock clock;
    private final TransactionTemplate tx;

    public AuthService(SocialIdentityVerifier verifier, UserRepository users, TokenIssuer tokens, Clock clock,
            TransactionTemplate tx) {
        this.verifier = verifier;
        this.users = users;
        this.tokens = tokens;
        this.clock = clock;
        this.tx = tx;
    }

    // 공급자 확인은 외부 HTTP 호출이므로 DB 트랜잭션 밖에서 하고, 확인된 뒤에만 짧게 저장한다.
    public Tokens login(Provider provider, String credential) {
        String providerUserId = verifier.verify(provider, credential);
        return tx.execute(status -> startSession(provider, providerUserId));
    }

    private Tokens startSession(Provider provider, String providerUserId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long userId = users.upsertForLogin(provider.name(), providerUserId, now);

        UUID sessionId = UUID.randomUUID();
        String refreshToken = tokens.newRefreshToken(userId);
        users.startSession(userId, sessionId, tokens.hash(refreshToken), now.plus(TokenIssuer.REFRESH_TOKEN_TTL));
        return new Tokens(tokens.issueAccessToken(userId, sessionId), refreshToken);
    }

    @Transactional
    public Tokens refresh(String refreshToken) {
        long userId = tokens.refreshTokenUserId(refreshToken).orElseThrow(ApiException::unauthorized);
        OffsetDateTime now = OffsetDateTime.now(clock);
        String newRefreshToken = tokens.newRefreshToken(userId);
        int updated = users.rotateRefreshToken(userId, tokens.hash(refreshToken), tokens.hash(newRefreshToken),
                now.plus(TokenIssuer.REFRESH_TOKEN_TTL), now);
        if (updated != 1) {
            throw ApiException.unauthorized();
        }
        UUID sessionId = users.findById(userId).orElseThrow().activeSessionId();
        return new Tokens(tokens.issueAccessToken(userId, sessionId), newRefreshToken);
    }

    @Transactional
    public void logout(String refreshToken) {
        long userId = tokens.refreshTokenUserId(refreshToken).orElseThrow(ApiException::unauthorized);
        if (users.endSession(userId, tokens.hash(refreshToken), OffsetDateTime.now(clock)) != 1) {
            throw ApiException.unauthorized();
        }
    }

    @Transactional(readOnly = true)
    public boolean isActiveSession(long userId, UUID sessionId) {
        return users.hasActiveSession(userId, sessionId);
    }
}
