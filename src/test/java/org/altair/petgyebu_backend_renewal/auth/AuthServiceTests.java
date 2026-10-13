package org.altair.petgyebu_backend_renewal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.altair.petgyebu_backend_renewal.support.MutableClock;
import org.altair.petgyebu_backend_renewal.support.TestAuthConfig;
import org.altair.petgyebu_backend_renewal.user.User;
import org.altair.petgyebu_backend_renewal.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
@Import(TestAuthConfig.class)
class AuthServiceTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired AuthService auth;
    @Autowired TokenIssuer tokens;
    @Autowired UserRepository users;
    @Autowired JwtDecoder decoder;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE expenses, monthly_budgets, users RESTART IDENTITY");
        clock.set(TestAuthConfig.START);
    }

    @Test
    void firstLoginCreatesUserWithSession() {
        AuthService.Tokens issued = auth.login(Provider.KAKAO, "ok:k1");

        long userId = Long.parseLong(decoder.decode(issued.accessToken()).getSubject());
        User user = users.findById(userId).orElseThrow();
        assertThat(user.provider()).isEqualTo("KAKAO");
        assertThat(user.providerUserId()).isEqualTo("k1");
        assertThat(user.petType()).isNull();
        assertThat(user.lastLoginTime().toInstant()).isEqualTo(TestAuthConfig.START);
        assertThat(user.activeSessionId().toString())
                .isEqualTo(decoder.decode(issued.accessToken()).getClaimAsString("sid"));
        assertThat(user.refreshTokenHash()).isEqualTo(tokens.hash(issued.refreshToken()));
        assertThat(user.refreshTokenHash()).isNotEqualTo(issued.refreshToken());
        assertThat(user.refreshTokenExpiresAt().toInstant())
                .isEqualTo(TestAuthConfig.START.plus(Duration.ofDays(30)));
    }

    @Test
    void laterLoginReusesUserAndReplacesSession() {
        AuthService.Tokens first = auth.login(Provider.KAKAO, "ok:k1");
        long firstId = userId(first);
        java.util.UUID firstSession = sessionId(first);
        clock.advance(Duration.ofHours(1));
        AuthService.Tokens second = auth.login(Provider.KAKAO, "ok:k1");

        assertThat(userId(second)).isEqualTo(firstId);
        User user = users.findById(firstId).orElseThrow();
        assertThat(user.lastLoginTime().toInstant()).isEqualTo(TestAuthConfig.START.plus(Duration.ofHours(1)));
        assertThat(auth.isActiveSession(firstId, firstSession)).isFalse();
        assertThat(auth.isActiveSession(firstId, sessionId(second))).isTrue();
        assertUnauthorized(() -> auth.refresh(first.refreshToken()));
    }

    @Test
    void sameProviderUserIdOnDifferentProvidersAreSeparateAccounts() {
        long kakao = userId(auth.login(Provider.KAKAO, "ok:same"));
        long google = userId(auth.login(Provider.GOOGLE, "ok:same"));

        assertThat(kakao).isNotEqualTo(google);
    }

    @Test
    void failedVerificationCreatesNoUser() {
        assertThatThrownBy(() -> auth.login(Provider.KAKAO, "bad"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("SOCIAL_LOGIN_FAILED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isZero();
    }

    @Test
    void refreshRotatesRefreshTokenAndKeepsSession() {
        AuthService.Tokens login = auth.login(Provider.KAKAO, "ok:k1");
        java.util.UUID loginSession = sessionId(login);
        clock.advance(Duration.ofMinutes(20));

        AuthService.Tokens refreshed = auth.refresh(login.refreshToken());

        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThat(sessionId(refreshed)).isEqualTo(loginSession);
        assertThat(decoder.decode(refreshed.accessToken()).getExpiresAt())
                .isEqualTo(TestAuthConfig.START.plus(Duration.ofMinutes(35)));
        assertUnauthorized(() -> auth.refresh(login.refreshToken()));
    }

    @Test
    void refreshTokenExpiresThirtyDaysAfterLastUse() {
        AuthService.Tokens login = auth.login(Provider.KAKAO, "ok:k1");
        clock.advance(Duration.ofDays(29));
        AuthService.Tokens refreshed = auth.refresh(login.refreshToken());

        clock.advance(Duration.ofDays(30).minusSeconds(1));
        AuthService.Tokens again = auth.refresh(refreshed.refreshToken());

        clock.advance(Duration.ofDays(30));
        assertUnauthorized(() -> auth.refresh(again.refreshToken()));
    }

    @Test
    void rejectsMalformedOrForeignRefreshTokens() {
        AuthService.Tokens a = auth.login(Provider.KAKAO, "ok:a");
        long otherUser = userId(auth.login(Provider.KAKAO, "ok:b"));
        String secretOfA = a.refreshToken().substring(a.refreshToken().indexOf('.') + 1);

        for (String bad : new String[] {
                "", "garbage", "99999999999999999999.x", a.accessToken(), otherUser + "." + secretOfA,
                "999." + secretOfA}) {
            assertUnauthorized(() -> auth.refresh(bad));
        }
    }

    @Test
    void logoutEndsSession() {
        AuthService.Tokens login = auth.login(Provider.KAKAO, "ok:k1");
        long userId = userId(login);

        auth.logout(login.refreshToken());

        User user = users.findById(userId).orElseThrow();
        assertThat(user.activeSessionId()).isNull();
        assertThat(user.refreshTokenHash()).isNull();
        assertThat(user.refreshTokenExpiresAt()).isNull();
        assertThat(auth.isActiveSession(userId, sessionId(login))).isFalse();
        assertUnauthorized(() -> auth.refresh(login.refreshToken()));
        assertUnauthorized(() -> auth.logout(login.refreshToken()));
    }

    @Test
    void concurrentFirstLoginsCreateOneUser() throws Exception {
        List<AuthService.Tokens> results = runConcurrently(8, () -> auth.login(Provider.KAKAO, "ok:race"));

        assertThat(results).hasSize(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentRefreshWithSameTokenSucceedsOnce() throws Exception {
        String refreshToken = auth.login(Provider.KAKAO, "ok:k1").refreshToken();

        List<AuthService.Tokens> results = runConcurrently(8, () -> auth.refresh(refreshToken));

        assertThat(results).hasSize(1);
    }

    private <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        ready.await();
        go.countDown();
        List<T> successes = new ArrayList<>();
        for (Future<T> future : futures) {
            try {
                successes.add(future.get());
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(e.getCause()).isInstanceOfSatisfying(ApiException.class,
                        api -> assertThat(api.code()).isEqualTo("UNAUTHORIZED"));
            }
        }
        pool.shutdown();
        return successes;
    }

    private long userId(AuthService.Tokens issued) {
        return Long.parseLong(decoder.decode(issued.accessToken()).getSubject());
    }

    private java.util.UUID sessionId(AuthService.Tokens issued) {
        return java.util.UUID.fromString(decoder.decode(issued.accessToken()).getClaimAsString("sid"));
    }

    private void assertUnauthorized(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("UNAUTHORIZED"));
    }
}
