package org.altair.petgyebu_backend_renewal.auth;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// 카카오 앱 ID와 구글 클라이언트 ID는 공개 값이다. 비어 있으면 해당 공급자 로그인은 503이다.
@ConfigurationProperties("petgyebu.social")
public record SocialLoginProperties(@DefaultValue Kakao kakao, @DefaultValue Google google) {

    public record Kakao(String appId, @DefaultValue("https://kapi.kakao.com") URI baseUrl) {

        public Kakao {
            appId = appId == null ? "" : appId.strip();
        }
    }

    public record Google(List<String> clientIds,
            @DefaultValue("https://www.googleapis.com/oauth2/v3/certs") URI jwkSetUri) {

        public Google {
            clientIds = clientIds == null ? List.of()
                    : clientIds.stream().map(String::strip).filter(id -> !id.isEmpty()).toList();
        }
    }
}
