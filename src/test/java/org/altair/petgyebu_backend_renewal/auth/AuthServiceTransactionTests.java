package org.altair.petgyebu_backend_renewal.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
class AuthServiceTransactionTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static final AtomicReference<Boolean> transactionActiveDuringVerify = new AtomicReference<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingVerifierConfig {

        @Bean
        @Primary
        SocialIdentityVerifier recordingVerifier() {
            return (provider, credential) -> {
                transactionActiveDuringVerify.set(TransactionSynchronizationManager.isActualTransactionActive());
                return "tx-check-user";
            };
        }
    }

    @Autowired AuthService auth;

    @Test
    void providerIsCalledOutsideDatabaseTransaction() {
        AuthService.Tokens tokens = auth.login(Provider.KAKAO, "anything");

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(transactionActiveDuringVerify.get()).isFalse();
    }
}
