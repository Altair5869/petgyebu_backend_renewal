package org.altair.petgyebu_backend_renewal.auth;

import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.springframework.stereotype.Component;

// 실제 카카오·구글 연동 전까지 모든 로그인을 거부한다.
@Component
public class UnavailableSocialIdentityVerifier implements SocialIdentityVerifier {

    @Override
    public String verify(Provider provider, String credential) {
        throw ApiException.socialLoginUnavailable();
    }
}
