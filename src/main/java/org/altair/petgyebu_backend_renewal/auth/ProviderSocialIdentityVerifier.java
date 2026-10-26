package org.altair.petgyebu_backend_renewal.auth;

import org.altair.petgyebu_backend_renewal.common.ApiException;

// 운영 공급자 확인: 카카오 액세스 토큰, 구글 ID 토큰을 검증하고 저장 가능한 사용자 ID만 돌려준다.
public class ProviderSocialIdentityVerifier implements SocialIdentityVerifier {

    static final int MAX_PROVIDER_USER_ID_LENGTH = 255;

    private final SocialIdentityVerifier delegate;

    ProviderSocialIdentityVerifier(KakaoTokenVerifier kakao, GoogleIdTokenVerifier google) {
        this.delegate = requireStorable((provider, credential) -> switch (provider) {
            case KAKAO -> kakao.verify(credential);
            case GOOGLE -> google.verify(credential);
        });
    }

    @Override
    public String verify(Provider provider, String credential) {
        return delegate.verify(provider, credential);
    }

    // provider_user_id는 VARCHAR(255)다. null·빈 값·더 긴 ID는 저장할 수 없으므로 로그인 실패로 본다.
    static SocialIdentityVerifier requireStorable(SocialIdentityVerifier verifier) {
        return (provider, credential) -> {
            String id = verifier.verify(provider, credential);
            if (id == null || id.isBlank() || id.length() > MAX_PROVIDER_USER_ID_LENGTH) {
                throw ApiException.socialLoginFailed();
            }
            return id;
        };
    }
}
