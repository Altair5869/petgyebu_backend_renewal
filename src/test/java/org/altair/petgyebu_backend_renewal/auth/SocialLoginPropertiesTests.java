package org.altair.petgyebu_backend_renewal.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SocialLoginPropertiesTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    void defaultsToEmptyConfigAndRealProviderEndpoints() {
        runner.run(context -> {
            SocialLoginProperties props = context.getBean(SocialLoginProperties.class);
            assertThat(props.kakao().appId()).isEmpty();
            assertThat(props.kakao().baseUrl()).isEqualTo(URI.create("https://kapi.kakao.com"));
            assertThat(props.google().clientIds()).isEmpty();
            assertThat(props.google().jwkSetUri())
                    .isEqualTo(URI.create("https://www.googleapis.com/oauth2/v3/certs"));
        });
    }

    @Test
    void trimsValuesAndDropsBlankClientIds() {
        runner.withPropertyValues(
                        "petgyebu.social.kakao.app-id= 123 ",
                        "petgyebu.social.google.client-ids=a.apps.googleusercontent.com, ,b.apps.googleusercontent.com ")
                .run(context -> {
                    SocialLoginProperties props = context.getBean(SocialLoginProperties.class);
                    assertThat(props.kakao().appId()).isEqualTo("123");
                    assertThat(props.google().clientIds())
                            .containsExactly("a.apps.googleusercontent.com", "b.apps.googleusercontent.com");
                });
    }

    @Test
    void emptyEnvironmentValuesBindAsEmpty() {
        runner.withPropertyValues("petgyebu.social.kakao.app-id=", "petgyebu.social.google.client-ids=")
                .run(context -> {
                    SocialLoginProperties props = context.getBean(SocialLoginProperties.class);
                    assertThat(props.kakao().appId()).isEmpty();
                    assertThat(props.google().clientIds()).isEmpty();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SocialLoginProperties.class)
    static class PropertiesConfig {
    }
}
