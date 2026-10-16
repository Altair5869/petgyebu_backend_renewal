package org.altair.petgyebu_backend_renewal;

import java.io.IOException;
import org.altair.petgyebu_backend_renewal.auth.SocialIdentityVerifier;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

// `./gradlew bootTestRun` 전용 로컬 실행 진입점. 테스트 소스에만 있어 배포 jar에 들어가지 않는다.
// 실제 소셜 검증 대신 credential "ok:<providerUserId>"를 받아들이는 가짜 검증기를 쓰고, 시계는 실제 시계를 쓴다.
public class LocalDevApplication {

    public static void main(String[] args) {
        // 테스트 설정(config/application.yaml)의 테스트용 비밀값으로 조용히 실행되지 않도록 환경 변수를 요구한다.
        // 환경 변수는 설정 파일보다 우선하므로 값이 있으면 그 값이 쓰인다.
        String secret = System.getenv("PETGYEBU_JWT_SECRET");
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("PETGYEBU_JWT_SECRET environment variable is required for bootTestRun");
        }
        SpringApplication app = new SpringApplication(PetgyebuBackendRenewalApplication.class, LocalDevConfig.class);
        // 컴포넌트 스캔에서 테스트 클래스(TestAuthConfig의 고정 시계, 테스트용 컨트롤러 등)를 제외한다.
        app.addInitializers(context -> context.getBeanFactory()
                .registerSingleton("localDevTestClassesExcludeFilter", new TestClassesExcludeFilter()));
        app.run(args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalDevConfig {

        @Bean
        @Primary
        SocialIdentityVerifier localFakeSocialIdentityVerifier() {
            return (provider, credential) -> {
                if (credential.startsWith("ok:")) {
                    return credential.substring(3);
                }
                throw ApiException.socialLoginFailed();
            };
        }
    }

    static final class TestClassesExcludeFilter extends TypeExcludeFilter {

        private final String testClassesRoot =
                LocalDevApplication.class.getProtectionDomain().getCodeSource().getLocation().toString();

        @Override
        public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory)
                throws IOException {
            return metadataReader.getResource().getURL().toString().startsWith(testClassesRoot);
        }

        @Override
        public boolean equals(Object obj) {
            return obj != null && obj.getClass() == getClass();
        }

        @Override
        public int hashCode() {
            return getClass().hashCode();
        }
    }
}
