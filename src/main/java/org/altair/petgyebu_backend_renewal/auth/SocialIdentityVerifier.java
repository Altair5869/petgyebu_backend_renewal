package org.altair.petgyebu_backend_renewal.auth;

/**
 * 공급자 인증 결과를 확인해 공급자가 부여한 사용자 ID를 돌려준다.
 * credential은 카카오는 SDK 액세스 토큰, 구글은 ID 토큰이다.
 * 확인에 실패하면 ApiException.socialLoginFailed()를 던진다.
 */
public interface SocialIdentityVerifier {

    String verify(Provider provider, String credential);
}
