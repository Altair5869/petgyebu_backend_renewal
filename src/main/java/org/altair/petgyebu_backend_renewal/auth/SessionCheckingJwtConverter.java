package org.altair.petgyebu_backend_renewal.auth;

import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

// 서명·만료 검증을 통과한 JWT의 세션이 현재 활성 로그인인지 확인한다.
@Component
public class SessionCheckingJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AuthService auth;

    public SessionCheckingJwtConverter(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        long userId;
        UUID sessionId;
        try {
            userId = Long.parseLong(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getClaimAsString("sid"));
        } catch (RuntimeException e) {
            throw new BadCredentialsException("Invalid token claims");
        }
        if (!auth.isActiveSession(userId, sessionId)) {
            throw new BadCredentialsException("Inactive session");
        }
        return UsernamePasswordAuthenticationToken.authenticated(new AuthUser(userId), null, List.of());
    }
}
