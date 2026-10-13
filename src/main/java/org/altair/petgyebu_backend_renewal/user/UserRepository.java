package org.altair.petgyebu_backend_renewal.user;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

public interface UserRepository extends Repository<User, Long> {

    Optional<User> findById(Long id);

    @Query("""
            INSERT INTO users (provider, provider_user_id, last_login_time)
            VALUES (:provider, :providerUserId, :now)
            ON CONFLICT (provider, provider_user_id) DO UPDATE SET last_login_time = EXCLUDED.last_login_time
            RETURNING id
            """)
    long upsertForLogin(String provider, String providerUserId, OffsetDateTime now);

    @Modifying
    @Query("""
            UPDATE users
            SET active_session_id = :sessionId, refresh_token_hash = :refreshTokenHash,
                refresh_token_expires_at = :refreshTokenExpiresAt
            WHERE id = :id
            """)
    int startSession(long id, UUID sessionId, String refreshTokenHash, OffsetDateTime refreshTokenExpiresAt);

    @Modifying
    @Query("""
            UPDATE users
            SET refresh_token_hash = :newHash, refresh_token_expires_at = :newExpiresAt
            WHERE id = :id AND refresh_token_hash = :oldHash AND refresh_token_expires_at > :now
            """)
    int rotateRefreshToken(long id, String oldHash, String newHash, OffsetDateTime newExpiresAt, OffsetDateTime now);

    @Modifying
    @Query("""
            UPDATE users
            SET active_session_id = NULL, refresh_token_hash = NULL, refresh_token_expires_at = NULL
            WHERE id = :id AND refresh_token_hash = :hash AND refresh_token_expires_at > :now
            """)
    int endSession(long id, String hash, OffsetDateTime now);

    @Query("SELECT EXISTS (SELECT 1 FROM users WHERE id = :id AND active_session_id = :sessionId)")
    boolean hasActiveSession(long id, UUID sessionId);
}
