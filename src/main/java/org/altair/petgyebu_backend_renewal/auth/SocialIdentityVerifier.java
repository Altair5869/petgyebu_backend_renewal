package org.altair.petgyebu_backend_renewal.auth;

/**
 * 공급자 인증 결과를 확인해 공급자가 부여한 사용자 ID를 돌려준다.
 * credential의 의미(인가 코드 또는 공급자 토큰)는 앱인토스 연동 결정 전까지 잠정이다.
 * 확인에 실패하면 ApiException.socialLoginFailed()를 던진다.
 */
public interface SocialIdentityVerifier {

    String verify(Provider provider, String credential);
}
