package org.altair.petgyebu_backend_renewal.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class JwtPropertiesTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    void failsToStartWithoutSecret() {
        // 개발자 셸의 PETGYEBU_JWT_SECRET 환경 변수가 바인딩되지 않도록 빈 값으로 덮는다.
        runner.withPropertyValues("petgyebu.jwt.secret=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsToStartWithSecretShorterThan32Bytes() {
        runner.withPropertyValues("petgyebu.jwt.secret=" + "a".repeat(31))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void startsWith32ByteSecret() {
        runner.withPropertyValues("petgyebu.jwt.secret=" + "a".repeat(32))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JwtProperties.class).secretKey().getAlgorithm())
                            .isEqualTo("HmacSHA256");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class PropertiesConfig {
    }
}
