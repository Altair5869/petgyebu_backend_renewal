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

    // 로그·예외 메시지·테스트 실패 출력에 소셜 식별자와 세션 값이 남지 않도록 뺀다.
    @Override
    public String toString() {
        return "User[id=" + id + ", provider=" + provider + ", petType=" + petType
                + ", createdAt=" + createdAt + ", lastLoginTime=" + lastLoginTime
                + ", refreshTokenExpiresAt=" + refreshTokenExpiresAt + "]";
    }
}
