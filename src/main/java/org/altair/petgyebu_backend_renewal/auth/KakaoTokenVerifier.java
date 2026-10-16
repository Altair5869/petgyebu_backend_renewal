package org.altair.petgyebu_backend_renewal.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.time.Duration;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// 카카오 SDK가 발급한 액세스 토큰을 토큰 정보 API로 확인한다. 우리 앱(app_id)의 토큰만 받는다.
class KakaoTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(KakaoTokenVerifier.class);

    private record TokenInfo(Long id, @JsonProperty("app_id") Long appId) {
    }

    private final RestClient client;
    private final String appId;

    KakaoTokenVerifier(URI baseUrl, String appId, Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.client = RestClient.builder().baseUrl(baseUrl.toString()).requestFactory(requestFactory).build();
        this.appId = appId;
    }

    String verify(String accessToken) {
        if (appId.isEmpty()) {
            throw ApiException.socialLoginUnavailable();
        }
        if (accessToken.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.socialLoginFailed();
        }
        TokenInfo info;
        try {
            info = client.get()
                    .uri("/v1/user/access_token_info")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .onStatus(status -> status.value() == 400 || status.value() == 401, (request, response) -> {
                        log.warn("Kakao rejected access token: status={}", response.getStatusCode().value());
                        throw ApiException.socialLoginFailed();
                    })
                    .body(TokenInfo.class);
        } catch (RestClientException e) {
            log.warn("Kakao token check unavailable: {}", e.getClass().getSimpleName());
            throw ApiException.socialLoginUnavailable();
        }
        if (info == null || info.id() == null || info.appId() == null) {
            log.warn("Kakao token info response missing id or app_id");
            throw ApiException.socialLoginUnavailable();
        }
        if (!appId.equals(String.valueOf(info.appId()))) {
            log.warn("Kakao access token issued for another app");
            throw ApiException.socialLoginFailed();
        }
        return String.valueOf(info.id());
    }
}
