package org.altair.petgyebu_backend_renewal.auth;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("petgyebu.jwt")
public record JwtProperties(String secret) {

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "petgyebu.jwt.secret (PETGYEBU_JWT_SECRET) must be at least 32 bytes");
        }
    }

    // 서명 키가 로그·예외 메시지에 남지 않도록 값을 뺀다.
    @Override
    public String toString() {
        return "JwtProperties[secret=***]";
    }

    public SecretKey secretKey() {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
