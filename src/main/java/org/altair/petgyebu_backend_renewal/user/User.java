package org.altair.petgyebu_backend_renewal.user;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("users")
public record User(
        @Id Long id,
        String provider,
        String providerUserId,
        String petType,
        OffsetDateTime createdAt,
        OffsetDateTime lastLoginTime,
        UUID activeSessionId,
        String refreshTokenHash,
        OffsetDateTime refreshTokenExpiresAt) {
}
