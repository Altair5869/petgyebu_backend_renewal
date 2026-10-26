# M-003 Auth & Session Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로그인·토큰 갱신·로그아웃 API와 자체 액세스(JWT)·리프레시 토큰, 한 활성 로그인, Bearer 인증 필터, 공통 오류 응답을 구현한다. 실제 카카오·구글 연동은 `SocialIdentityVerifier` 뒤로 미룬다.

**Architecture:** `auth` 패키지가 토큰(`TokenIssuer`, `JwtConfig`), 세션 흐름(`AuthService`), 웹·보안(`AuthController`, `SecurityConfig`, `SessionCheckingJwtConverter`)을 맡는다. `user` 패키지의 `User`/`UserRepository`(Spring Data JDBC, 명시적 `@Query`)가 `users` 행을 다룬다. `common` 패키지가 `Clock` 빈과 공통 오류(`ApiException`, `ApiError`, `ApiExceptionHandler`)를 둔다.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring Security 7.1 OAuth2 Resource Server(Nimbus JOSE, HS256), Spring Data JDBC, PostgreSQL 16, Flyway, JUnit 5, Testcontainers, MockMvc, json-path.

**Spec:** `docs/superpowers/specs/2026-10-13-m003-auth-session-design.md`

## Global Constraints

- 기본 패키지: `org.altair.petgyebu_backend_renewal`. 작업 브랜치: `feature/F-M01-auth-session` (`develop`에서 분기).
- 경로 접두사 `/api/v1`. 로그인 `POST /auth/login/{provider}` 본문 `{credential}`(잠정), 갱신·로그아웃 본문 `{refreshToken}`.
- 로그인·갱신 응답: `{accessToken, refreshToken, accessExpiresInSeconds}`; `accessExpiresInSeconds = 900`. 로그아웃 204.
- 액세스 토큰: HS256 JWT, `sub` = userId(문자열), `sid` = 세션 UUID, 만료 정확히 15분. 허용 시계 오차 0 (`now >= exp`이면 거부).
- 리프레시 토큰: `{userId}.{32바이트 SecureRandom, base64url 패딩 없음}`. DB에는 토큰 전체 문자열의 SHA-256 소문자 16진수(64자)만 저장. 만료는 마지막 사용 후 30일, `expires_at > now`일 때만 유효.
- 오류 JSON: `{code, message}`, 입력 오류는 `fields: [{field, message}]` 추가. 코드: `INVALID_REQUEST`(400), `SOCIAL_LOGIN_FAILED`(401), `UNAUTHORIZED`(401), `SOCIAL_LOGIN_UNAVAILABLE`(503).
- 비밀값: `petgyebu.jwt.secret` ← 환경 변수 `PETGYEBU_JWT_SECRET`. 없거나 UTF-8 32바이트 미만이면 시작 실패. 토큰·credential은 로그에 남기지 않는다.
- 추가 의존성은 `org.springframework.boot:spring-boot-starter-security-oauth2-resource-server` 하나뿐이다. DB 스키마는 바꾸지 않는다(`V1__baseline.sql` 그대로).
- 모든 시각은 주입한 `Clock`에서 얻는다(`OffsetDateTime.now(clock)`, `clock.instant()`).
- `karpathy-guidelines`를 적용한다: 요청 범위 밖 기능·추상화 금지, 기존 코드 스타일 유지.
- 완료 전 `./gradlew cleanTest test --no-daemon` 통과 (Docker 필요).

## Review Focus

1. `alg: none` 또는 다른 키로 서명한 액세스 토큰 → 401이어야 한다 (Task 2, Task 4 테스트).
2. `Long` 범위를 넘는 userId(`99999999999999999999.x`)나 JWT 문자열을 리프레시 토큰으로 보냄 → 500이 아니라 401 (Task 2 파싱 테스트, Task 3 서비스 테스트).
3. 사용자 행이 삭제된 뒤의 액세스 토큰 → 500이 아니라 401 (Task 4 테스트).
4. 리프레시 토큰을 `Authorization: Bearer`로 보냄 → 401 (Task 4 테스트).
5. 로그인에 `Content-Type: text/plain` 또는 본문 없음 → 401로 가려지지 않고 400/415 (Task 4 테스트).

---

## File Structure

| 파일 | 책임 |
|---|---|
| `build.gradle` (수정) | resource server starter 추가 |
| `src/main/resources/application.yaml` (수정) | `petgyebu.jwt.secret: ${PETGYEBU_JWT_SECRET:}` |
| `src/test/resources/config/application.yaml` (생성) | 테스트 전용 서명 키 (main 설정에 병합됨) |
| `common/ClockConfig.java` | `Clock.systemUTC()` 빈 |
| `common/ApiError.java` | 오류 응답 레코드 |
| `common/ApiException.java` | 상태·코드·메시지·필드를 가진 예외와 팩토리 |
| `common/ApiExceptionHandler.java` | 컨트롤러 예외 → `ApiError` |
| `auth/JwtProperties.java` | 서명 키 설정과 길이 검증 |
| `auth/JwtConfig.java` | `JwtEncoder`, `JwtDecoder`(만료 검증, 오차 0) 빈 |
| `auth/TokenIssuer.java` | 액세스 JWT 발급, 리프레시 토큰 생성·파싱·해시 |
| `auth/Provider.java` | `KAKAO`, `GOOGLE`, 대소문자 무시 파싱 |
| `auth/SocialIdentityVerifier.java` | 공급자 확인 경계 |
| `auth/UnavailableSocialIdentityVerifier.java` | 운영 기본 구현 (503) |
| `auth/AuthService.java` | 로그인·갱신·로그아웃·세션 확인 |
| `auth/AuthController.java` | 세 API와 요청·응답 레코드 |
| `auth/AuthUser.java` | 인증된 사용자 principal |
| `auth/SessionCheckingJwtConverter.java` | JWT → `AuthUser`, `sid` DB 확인 |
| `auth/SecurityConfig.java` | 무상태 필터 체인, JSON 401 진입점 |
| `user/User.java`, `user/UserRepository.java` | `users` 집합과 명시적 쿼리 |
| 테스트 `support/MutableClock.java` | 앞당길 수 있는 시계 |
| 테스트 `support/TestAuthConfig.java` | `@Primary` MutableClock, 가짜 verifier |
| 테스트 `support/TestMeController.java` | 테스트 전용 보호 엔드포인트 `GET /api/v1/test/me` |

(`common/`, `auth/`, `user/`, `support/`는 모두 `org/altair/petgyebu_backend_renewal/` 아래.)

---

### Task 1: 의존성, 서명 키 설정, Clock 빈

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/resources/application.yaml`
- Create: `src/test/resources/config/application.yaml`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/common/ClockConfig.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/auth/JwtProperties.java`
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/auth/JwtPropertiesTests.java`

**Interfaces:**
- Produces: `JwtProperties(String secret)` 레코드와 `SecretKey secretKey()` (HmacSHA256). `Clock` 빈 (`Clock.systemUTC()`).

- [ ] **Step 1: 브랜치 생성**

```bash
git switch develop && git pull --ff-only && git switch -c feature/F-M01-auth-session
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/org/altair/petgyebu_backend_renewal/auth/JwtPropertiesTests.java`:

```java
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
        runner.run(context -> assertThat(context).hasFailed());
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
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew test --no-daemon --tests '*JwtPropertiesTests'`
Expected: 컴파일 실패 (`JwtProperties` 없음)

- [ ] **Step 4: 구현**

`build.gradle`의 `dependencies`에 기존 security starter 바로 아래 한 줄 추가:

```groovy
    implementation 'org.springframework.boot:spring-boot-starter-security-oauth2-resource-server'
```

`src/main/resources/application.yaml` 전체:

```yaml
spring:
  application:
    name: petgyebu_backend_renewal

petgyebu:
  jwt:
    secret: ${PETGYEBU_JWT_SECRET:}
```

`src/test/resources/config/application.yaml` (테스트 클래스패스에서만 main 설정 위에 병합된다):

```yaml
# 테스트 전용 값. 운영 비밀값이 아니다.
petgyebu:
  jwt:
    secret: test-only-jwt-secret-not-for-production-use
```

`common/ClockConfig.java`:

```java
package org.altair.petgyebu_backend_renewal.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

`auth/JwtProperties.java`:

```java
package org.altair.petgyebu_backend_renewal.auth;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("petgyebu.jwt")
public record JwtProperties(String secret) {

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "petgyebu.jwt.secret (PETGYEBU_JWT_SECRET) must be at least 32 bytes");
        }
    }

    public SecretKey secretKey() {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew test --no-daemon --tests '*JwtPropertiesTests'`
Expected: 3 tests PASS

Run: `./gradlew cleanTest test --no-daemon`
Expected: 전체 PASS (기존 10개 + 3개). 기존 컨텍스트 테스트가 resource server starter 추가 후에도 뜨는지 확인한다.

- [ ] **Step 6: Commit**

```bash
git add build.gradle src/main/resources/application.yaml src/test/resources/config/application.yaml \
  src/main/java/org/altair/petgyebu_backend_renewal/common/ClockConfig.java \
  src/main/java/org/altair/petgyebu_backend_renewal/auth/JwtProperties.java \
  src/test/java/org/altair/petgyebu_backend_renewal/auth/JwtPropertiesTests.java
git commit -m "feat: add JWT secret settings and clock bean"
```

---

### Task 2: 토큰 발급·검증 (`JwtConfig`, `TokenIssuer`)

**Files:**
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/auth/JwtConfig.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/auth/TokenIssuer.java`
- Create: `src/test/java/org/altair/petgyebu_backend_renewal/support/MutableClock.java`
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/auth/TokenIssuerTests.java`

**Interfaces:**
- Consumes: `JwtProperties.secretKey()`, `Clock` 빈 (Task 1)
- Produces:
  - `JwtConfig` 빈: `JwtEncoder jwtEncoder(JwtProperties)`, `JwtDecoder jwtDecoder(JwtProperties, Clock)`; 정적 `OAuth2TokenValidator<Jwt> notExpired(Clock)`
  - `TokenIssuer` (`@Component`): `static final Duration ACCESS_TOKEN_TTL` (15분), `static final Duration REFRESH_TOKEN_TTL` (30일), `String issueAccessToken(long userId, UUID sessionId)`, `String newRefreshToken(long userId)`, `Optional<Long> refreshTokenUserId(String refreshToken)`, `String hash(String refreshToken)`
  - 테스트 `MutableClock(Instant start)`: `void advance(Duration)`, `instant()`, zone UTC

- [ ] **Step 1: MutableClock 작성**

`src/test/java/org/altair/petgyebu_backend_renewal/support/MutableClock.java`:

```java
package org.altair.petgyebu_backend_renewal.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

public class MutableClock extends Clock {

    private volatile Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    public void set(Instant instant) {
        now = instant;
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/org/altair/petgyebu_backend_renewal/auth/TokenIssuerTests.java`:

```java
package org.altair.petgyebu_backend_renewal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.altair.petgyebu_backend_renewal.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class TokenIssuerTests {

    private static final Instant START = Instant.parse("2026-10-13T00:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final JwtProperties properties = new JwtProperties("a".repeat(32));
    private final JwtConfig config = new JwtConfig();
    private final JwtDecoder decoder = config.jwtDecoder(properties, clock);
    private final TokenIssuer issuer = new TokenIssuer(config.jwtEncoder(properties), clock);

    @Test
    void accessTokenCarriesUserSessionAndFifteenMinuteExpiry() {
        UUID sessionId = UUID.randomUUID();

        Jwt jwt = decoder.decode(issuer.issueAccessToken(42L, sessionId));

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(sessionId.toString());
        assertThat(jwt.getExpiresAt()).isEqualTo(START.plus(Duration.ofMinutes(15)));
        assertThat(TokenIssuer.ACCESS_TOKEN_TTL.toSeconds()).isEqualTo(900);
    }

    @Test
    void accessTokenIsValidUntilJustBeforeFifteenMinutes() {
        String token = issuer.issueAccessToken(1L, UUID.randomUUID());

        clock.advance(Duration.ofMinutes(15).minusSeconds(1));
        assertThat(decoder.decode(token).getSubject()).isEqualTo("1");

        clock.advance(Duration.ofSeconds(1));
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        JwtProperties other = new JwtProperties("b".repeat(32));
        String foreign = new TokenIssuer(config.jwtEncoder(other), clock).issueAccessToken(1L, UUID.randomUUID());

        assertThatThrownBy(() -> decoder.decode(foreign)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsUnsignedToken() {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        long exp = START.plus(Duration.ofMinutes(15)).getEpochSecond();
        String payload = b64.encodeToString(("{\"sub\":\"1\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> decoder.decode(header + "." + payload + "."))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void refreshTokenEmbedsUserIdAndRandomSecret() {
        String first = issuer.newRefreshToken(42L);
        String second = issuer.newRefreshToken(42L);

        assertThat(first).matches("42\\.[A-Za-z0-9_-]{43}");
        assertThat(first).isNotEqualTo(second);
        assertThat(issuer.refreshTokenUserId(first)).contains(42L);
    }

    @Test
    void rejectsMalformedRefreshTokens() {
        String jwt = issuer.issueAccessToken(1L, UUID.randomUUID());

        for (String bad : new String[] {
                "", "garbage", "1.", ".secret", "abc.secret", "-1.secret", "0.secret",
                "99999999999999999999.secret", jwt}) {
            assertThat(issuer.refreshTokenUserId(bad)).as(bad).isEmpty();
        }
        assertThat(issuer.refreshTokenUserId(null)).isEmpty();
    }

    @Test
    void hashIsDeterministicSha256Hex() {
        String token = issuer.newRefreshToken(7L);

        assertThat(issuer.hash(token)).matches("[0-9a-f]{64}");
        assertThat(issuer.hash(token)).isEqualTo(issuer.hash(token));
        assertThat(issuer.hash(token)).isNotEqualTo(token);
        assertThat(issuer.hash(issuer.newRefreshToken(7L))).isNotEqualTo(issuer.hash(token));
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew test --no-daemon --tests '*TokenIssuerTests'`
Expected: 컴파일 실패 (`JwtConfig`, `TokenIssuer` 없음)

- [ ] **Step 4: 구현**

`auth/JwtConfig.java`:

```java
package org.altair.petgyebu_backend_renewal.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(properties.secretKey()));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(properties.secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(notExpired(clock));
        return decoder;
    }

    // 시계 오차 없이 exp 시각부터 거부한다 (RFC 7519 4.1.4).
    static OAuth2TokenValidator<Jwt> notExpired(Clock clock) {
        return jwt -> jwt.getExpiresAt() != null && clock.instant().isBefore(jwt.getExpiresAt())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token expired", null));
    }
}
```

`auth/TokenIssuer.java`:

```java
package org.altair.petgyebu_backend_renewal.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
public class TokenIssuer {

    public static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    public static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtEncoder encoder;
    private final Clock clock;

    public TokenIssuer(JwtEncoder encoder, Clock clock) {
        this.encoder = encoder;
        this.clock = clock;
    }

    public String issueAccessToken(long userId, UUID sessionId) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(Long.toString(userId))
                .claim("sid", sessionId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(ACCESS_TOKEN_TTL))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public String newRefreshToken(long userId) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return userId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    public Optional<Long> refreshTokenUserId(String refreshToken) {
        if (refreshToken == null) {
            return Optional.empty();
        }
        int dot = refreshToken.indexOf('.');
        if (dot <= 0 || dot == refreshToken.length() - 1) {
            return Optional.empty();
        }
        try {
            long userId = Long.parseLong(refreshToken.substring(0, dot));
            return userId > 0 ? Optional.of(userId) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public String hash(String refreshToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew test --no-daemon --tests '*TokenIssuerTests'`
Expected: 7 tests PASS. `rejectsUnsignedToken`이 실패하면 디코더 설정을 고치고, 테스트를 약하게 바꾸지 않는다.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/altair/petgyebu_backend_renewal/auth/JwtConfig.java \
  src/main/java/org/altair/petgyebu_backend_renewal/auth/TokenIssuer.java \
  src/test/java/org/altair/petgyebu_backend_renewal/support/MutableClock.java \
  src/test/java/org/altair/petgyebu_backend_renewal/auth/TokenIssuerTests.java
git commit -m "feat: issue HS256 access tokens and hashed refresh tokens"
```

---

### Task 3: 사용자 저장소와 세션 흐름 (`AuthService`)

**Files:**
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/common/ApiError.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/common/ApiException.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/user/User.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/user/UserRepository.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/auth/Provider.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/auth/SocialIdentityVerifier.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/auth/UnavailableSocialIdentityVerifier.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/auth/AuthService.java`
- Create: `src/test/java/org/altair/petgyebu_backend_renewal/support/TestAuthConfig.java`
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/auth/AuthServiceTests.java`
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/auth/UnavailableSocialIdentityVerifierTests.java`

**Interfaces:**
- Consumes: `TokenIssuer` 메서드와 TTL 상수 (Task 2), `MutableClock` (Task 2)
- Produces:
  - `ApiError(String code, String message, List<ApiError.FieldError> fields)`, `ApiError.FieldError(String field, String message)`
  - `ApiException(HttpStatus status, String code, String message, List<ApiError.FieldError> fields)`, getter `status()`, `code()`, `fields()`, `getMessage()`; 팩토리 `unauthorized()`, `socialLoginFailed()`, `socialLoginUnavailable()`, `invalidRequest(String field, String message)`
  - `Provider { KAKAO, GOOGLE }`, `static Optional<Provider> from(String)`
  - `SocialIdentityVerifier.verify(Provider, String credential) → String providerUserId`
  - `AuthService.Tokens(String accessToken, String refreshToken)`; `Tokens login(Provider, String credential)`, `Tokens refresh(String refreshToken)`, `void logout(String refreshToken)`, `boolean isActiveSession(long userId, UUID sessionId)`
  - `User` 레코드, `UserRepository`
  - 테스트 `TestAuthConfig`: `@Primary MutableClock`(시작 `2026-10-13T00:00:00Z`), `@Primary SocialIdentityVerifier` 가짜. 가짜 규칙: `"ok:<id>"` → `<id>`, `"unavailable"` → `socialLoginUnavailable()`, 그 밖 → `socialLoginFailed()`

- [ ] **Step 1: 테스트 지원 설정 작성**

`src/test/java/org/altair/petgyebu_backend_renewal/support/TestAuthConfig.java`:

```java
package org.altair.petgyebu_backend_renewal.support;

import java.time.Instant;
import org.altair.petgyebu_backend_renewal.auth.SocialIdentityVerifier;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestAuthConfig {

    public static final Instant START = Instant.parse("2026-10-13T00:00:00Z");

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(START);
    }

    @Bean
    @Primary
    SocialIdentityVerifier fakeSocialIdentityVerifier() {
        return (provider, credential) -> {
            if (credential.startsWith("ok:")) {
                return credential.substring(3);
            }
            if (credential.equals("unavailable")) {
                throw ApiException.socialLoginUnavailable();
            }
            throw ApiException.socialLoginFailed();
        };
    }
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/org/altair/petgyebu_backend_renewal/auth/UnavailableSocialIdentityVerifierTests.java`:

```java
package org.altair.petgyebu_backend_renewal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class UnavailableSocialIdentityVerifierTests {

    @Test
    void rejectsEveryLoginUntilProvidersAreIntegrated() {
        assertThatThrownBy(() -> new UnavailableSocialIdentityVerifier().verify(Provider.KAKAO, "anything"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.code()).isEqualTo("SOCIAL_LOGIN_UNAVAILABLE");
                });
    }
}
```

`src/test/java/org/altair/petgyebu_backend_renewal/auth/AuthServiceTests.java`:

```java
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
        clock.advance(Duration.ofHours(1));
        AuthService.Tokens second = auth.login(Provider.KAKAO, "ok:k1");

        long firstId = userId(first);
        assertThat(userId(second)).isEqualTo(firstId);
        User user = users.findById(firstId).orElseThrow();
        assertThat(user.lastLoginTime().toInstant()).isEqualTo(TestAuthConfig.START.plus(Duration.ofHours(1)));
        assertThat(auth.isActiveSession(firstId, sessionId(first))).isFalse();
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
        clock.advance(Duration.ofMinutes(20));

        AuthService.Tokens refreshed = auth.refresh(login.refreshToken());

        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThat(sessionId(refreshed)).isEqualTo(sessionId(login));
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
```

참고: `refreshRotatesRefreshTokenAndKeepsSession`에서 20분 뒤 갱신한 액세스 토큰의 만료는 `START + 35분`이다. 디코더는 `MutableClock` 기준이므로 그 시점에 유효하다. `refreshTokenExpiresThirtyDaysAfterLastUse`에서 디코더를 쓰지 않는 이유는 액세스 토큰이 이미 만료됐기 때문이다.

- [ ] **Step 3: 실패 확인**

Run: `./gradlew test --no-daemon --tests '*AuthServiceTests' --tests '*UnavailableSocialIdentityVerifierTests'`
Expected: 컴파일 실패 (`AuthService`, `ApiException` 등 없음)

- [ ] **Step 4: 구현**

`common/ApiError.java`:

```java
package org.altair.petgyebu_backend_renewal.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, List<FieldError> fields) {

    public record FieldError(String field, String message) {
    }
}
```

`common/ApiException.java`:

```java
package org.altair.petgyebu_backend_renewal.common;

import java.util.List;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ApiError.FieldError> fields;

    public ApiException(HttpStatus status, String code, String message, List<ApiError.FieldError> fields) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = fields;
    }

    public static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다.", null);
    }

    public static ApiException socialLoginFailed() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "SOCIAL_LOGIN_FAILED", "소셜 로그인을 확인할 수 없습니다.", null);
    }

    public static ApiException socialLoginUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SOCIAL_LOGIN_UNAVAILABLE",
                "소셜 로그인을 사용할 수 없습니다.", null);
    }

    public static ApiException invalidRequest(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값이 올바르지 않습니다.",
                List.of(new ApiError.FieldError(field, message)));
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<ApiError.FieldError> fields() {
        return fields;
    }
}
```

`user/User.java`:

```java
package org.altair.petgyebu_backend_renewal.user;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("users")
public record User(
        @Id Long id,
        String provider,
        String providerUserId,
        String petType,
        OffsetDateTime createdAt,
        OffsetDateTime lastLoginTime,
        UUID activeSessionId,
        String refreshTokenHash,
        OffsetDateTime refreshTokenExpiresAt) {
}
```

`user/UserRepository.java`:

```java
package org.altair.petgyebu_backend_renewal.user;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

public interface UserRepository extends Repository<User, Long> {

    Optional<User> findById(Long id);

    @Query("""
            INSERT INTO users (provider, provider_user_id, last_login_time)
            VALUES (:provider, :providerUserId, :now)
            ON CONFLICT (provider, provider_user_id) DO UPDATE SET last_login_time = EXCLUDED.last_login_time
            RETURNING id
            """)
    long upsertForLogin(String provider, String providerUserId, OffsetDateTime now);

    @Modifying
    @Query("""
            UPDATE users
            SET active_session_id = :sessionId, refresh_token_hash = :refreshTokenHash,
                refresh_token_expires_at = :refreshTokenExpiresAt
            WHERE id = :id
            """)
    int startSession(long id, UUID sessionId, String refreshTokenHash, OffsetDateTime refreshTokenExpiresAt);

    @Modifying
    @Query("""
            UPDATE users
            SET refresh_token_hash = :newHash, refresh_token_expires_at = :newExpiresAt
            WHERE id = :id AND refresh_token_hash = :oldHash AND refresh_token_expires_at > :now
            """)
    int rotateRefreshToken(long id, String oldHash, String newHash, OffsetDateTime newExpiresAt, OffsetDateTime now);

    @Modifying
    @Query("""
            UPDATE users
            SET active_session_id = NULL, refresh_token_hash = NULL, refresh_token_expires_at = NULL
            WHERE id = :id AND refresh_token_hash = :hash AND refresh_token_expires_at > :now
            """)
    int endSession(long id, String hash, OffsetDateTime now);

    @Query("SELECT EXISTS (SELECT 1 FROM users WHERE id = :id AND active_session_id = :sessionId)")
    boolean hasActiveSession(long id, UUID sessionId);
}
```

`auth/Provider.java`:

```java
package org.altair.petgyebu_backend_renewal.auth;

import java.util.Arrays;
import java.util.Optional;

public enum Provider {
    KAKAO, GOOGLE;

    public static Optional<Provider> from(String value) {
        return Arrays.stream(values()).filter(p -> p.name().equalsIgnoreCase(value)).findFirst();
    }
}
```

`auth/SocialIdentityVerifier.java`:

```java
package org.altair.petgyebu_backend_renewal.auth;

/**
 * 공급자 인증 결과를 확인해 공급자가 부여한 사용자 ID를 돌려준다.
 * credential의 의미(인가 코드 또는 공급자 토큰)는 앱인토스 연동 결정 전까지 잠정이다.
 * 확인에 실패하면 ApiException.socialLoginFailed()를 던진다.
 */
public interface SocialIdentityVerifier {

    String verify(Provider provider, String credential);
}
```

`auth/UnavailableSocialIdentityVerifier.java`:

```java
package org.altair.petgyebu_backend_renewal.auth;

import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.springframework.stereotype.Component;

// 실제 카카오·구글 연동 전까지 모든 로그인을 거부한다.
@Component
public class UnavailableSocialIdentityVerifier implements SocialIdentityVerifier {

    @Override
    public String verify(Provider provider, String credential) {
        throw ApiException.socialLoginUnavailable();
    }
}
```

`auth/AuthService.java`:

```java
package org.altair.petgyebu_backend_renewal.auth;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.altair.petgyebu_backend_renewal.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    public record Tokens(String accessToken, String refreshToken) {
    }

    private final SocialIdentityVerifier verifier;
    private final UserRepository users;
    private final TokenIssuer tokens;
    private final Clock clock;

    public AuthService(SocialIdentityVerifier verifier, UserRepository users, TokenIssuer tokens, Clock clock) {
        this.verifier = verifier;
        this.users = users;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Transactional
    public Tokens login(Provider provider, String credential) {
        String providerUserId = verifier.verify(provider, credential);
        OffsetDateTime now = OffsetDateTime.now(clock);
        long userId = users.upsertForLogin(provider.name(), providerUserId, now);

        UUID sessionId = UUID.randomUUID();
        String refreshToken = tokens.newRefreshToken(userId);
        users.startSession(userId, sessionId, tokens.hash(refreshToken), now.plus(TokenIssuer.REFRESH_TOKEN_TTL));
        return new Tokens(tokens.issueAccessToken(userId, sessionId), refreshToken);
    }

    @Transactional
    public Tokens refresh(String refreshToken) {
        long userId = tokens.refreshTokenUserId(refreshToken).orElseThrow(ApiException::unauthorized);
        OffsetDateTime now = OffsetDateTime.now(clock);
        String newRefreshToken = tokens.newRefreshToken(userId);
        int updated = users.rotateRefreshToken(userId, tokens.hash(refreshToken), tokens.hash(newRefreshToken),
                now.plus(TokenIssuer.REFRESH_TOKEN_TTL), now);
        if (updated != 1) {
            throw ApiException.unauthorized();
        }
        UUID sessionId = users.findById(userId).orElseThrow().activeSessionId();
        return new Tokens(tokens.issueAccessToken(userId, sessionId), newRefreshToken);
    }

    @Transactional
    public void logout(String refreshToken) {
        long userId = tokens.refreshTokenUserId(refreshToken).orElseThrow(ApiException::unauthorized);
        if (users.endSession(userId, tokens.hash(refreshToken), OffsetDateTime.now(clock)) != 1) {
            throw ApiException.unauthorized();
        }
    }

    @Transactional(readOnly = true)
    public boolean isActiveSession(long userId, UUID sessionId) {
        return users.hasActiveSession(userId, sessionId);
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew test --no-daemon --tests '*AuthServiceTests' --tests '*UnavailableSocialIdentityVerifierTests'`
Expected: 11 tests PASS.
실패 시 확인할 것: `upsertForLogin`의 `RETURNING id`가 Spring Data JDBC에서 단일 값으로 매핑되는지, `UUID`/`OffsetDateTime` 파라미터 바인딩. 쿼리를 바꿀 때 동시성 테스트 두 개가 계속 통과해야 한다.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/altair/petgyebu_backend_renewal/common \
  src/main/java/org/altair/petgyebu_backend_renewal/user \
  src/main/java/org/altair/petgyebu_backend_renewal/auth/Provider.java \
  src/main/java/org/altair/petgyebu_backend_renewal/auth/SocialIdentityVerifier.java \
  src/main/java/org/altair/petgyebu_backend_renewal/auth/UnavailableSocialIdentityVerifier.java \
  src/main/java/org/altair/petgyebu_backend_renewal/auth/AuthService.java \
  src/test/java/org/altair/petgyebu_backend_renewal/support/TestAuthConfig.java \
  src/test/java/org/altair/petgyebu_backend_renewal/auth/AuthServiceTests.java \
  src/test/java/org/altair/petgyebu_backend_renewal/auth/UnavailableSocialIdentityVerifierTests.java
git commit -m "feat: add login, refresh and logout session flow"
```

(`common/ClockConfig.java`는 Task 1에서 이미 커밋됐으므로 `git add`해도 변경이 없다.)

---

### Task 4: 인증 API와 보안 필터

**Files:**
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/common/ApiExceptionHandler.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/auth/AuthController.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/auth/AuthUser.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/auth/SessionCheckingJwtConverter.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/auth/SecurityConfig.java`
- Create: `src/test/java/org/altair/petgyebu_backend_renewal/support/TestMeController.java`
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/auth/AuthApiTests.java`

**Interfaces:**
- Consumes: `AuthService` (login/refresh/logout/isActiveSession, `Tokens`), `Provider.from`, `ApiException`, `ApiError`, `TokenIssuer.ACCESS_TOKEN_TTL`, `JwtDecoder` 빈, `TestAuthConfig`, `MutableClock`
- Produces: `AuthUser(long userId)` principal (`@AuthenticationPrincipal AuthUser`로 이후 컨트롤러가 사용), 공통 오류 처리

- [ ] **Step 1: 테스트 전용 보호 엔드포인트 작성**

`src/test/java/org/altair/petgyebu_backend_renewal/support/TestMeController.java`:

```java
package org.altair.petgyebu_backend_renewal.support;

import java.util.Map;
import org.altair.petgyebu_backend_renewal.auth.AuthUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// 테스트 클래스패스에만 있는 인증 확인용 엔드포인트.
@RestController
public class TestMeController {

    @GetMapping("/api/v1/test/me")
    Map<String, Long> me(@AuthenticationPrincipal AuthUser user) {
        return Map.of("userId", user.userId());
    }
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/org/altair/petgyebu_backend_renewal/auth/AuthApiTests.java`:

```java
package org.altair.petgyebu_backend_renewal.auth;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.UUID;
import org.altair.petgyebu_backend_renewal.support.MutableClock;
import org.altair.petgyebu_backend_renewal.support.TestAuthConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class AuthApiTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE expenses, monthly_budgets, users RESTART IDENTITY");
        clock.set(TestAuthConfig.START);
    }

    @Test
    void loginReturnsTokens() throws Exception {
        login("kakao", "ok:k1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.accessExpiresInSeconds").value(900));
        login("GOOGLE", "ok:g1").andExpect(status().isOk());
    }

    @Test
    void loginValidatesInput() throws Exception {
        login("apple", "ok:x")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fields[0].field").value("provider"));
        login("kakao", " ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fields[0].field").value("credential"));
        mvc.perform(post("/api/v1/auth/login/kakao").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/auth/login/kakao").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().is4xxClientError())
                .andExpect(status().is(not(401)));
    }

    @Test
    void loginReportsProviderFailures() throws Exception {
        login("kakao", "bad")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SOCIAL_LOGIN_FAILED"));
        login("kakao", "unavailable")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOCIAL_LOGIN_UNAVAILABLE"));
    }

    @Test
    void protectedEndpointRequiresValidAccessToken() throws Exception {
        String body = login("kakao", "ok:k1").andReturn().getResponse().getContentAsString();
        String access = JsonPath.read(body, "$.accessToken");
        String refresh = JsonPath.read(body, "$.refreshToken");

        me(access).andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(1));

        mvc.perform(get("/api/v1/test/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isString());
        expectUnauthorized(me("garbage"));
        expectUnauthorized(me(refresh));
        expectUnauthorized(me(foreignToken()));
    }

    @Test
    void accessTokenExpiresAtFifteenMinutes() throws Exception {
        String access = accessToken(login("kakao", "ok:k1"));

        clock.advance(Duration.ofMinutes(15).minusSeconds(1));
        me(access).andExpect(status().isOk());

        clock.advance(Duration.ofSeconds(1));
        expectUnauthorized(me(access));
    }

    @Test
    void newLoginInvalidatesPreviousTokens() throws Exception {
        String firstBody = login("kakao", "ok:k1").andReturn().getResponse().getContentAsString();
        String second = accessToken(login("kakao", "ok:k1"));

        expectUnauthorized(me(JsonPath.read(firstBody, "$.accessToken")));
        expectUnauthorized(refresh(JsonPath.read(firstBody, "$.refreshToken")));
        me(second).andExpect(status().isOk());
    }

    @Test
    void refreshIssuesNewTokens() throws Exception {
        String old = JsonPath.read(login("kakao", "ok:k1").andReturn().getResponse().getContentAsString(),
                "$.refreshToken");

        String body = refresh(old)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessExpiresInSeconds").value(900))
                .andReturn().getResponse().getContentAsString();

        me(JsonPath.read(body, "$.accessToken")).andExpect(status().isOk());
        expectUnauthorized(refresh(old));
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("refreshToken"));
    }

    @Test
    void logoutRevokesTokens() throws Exception {
        String body = login("kakao", "ok:k1").andReturn().getResponse().getContentAsString();
        String access = JsonPath.read(body, "$.accessToken");
        String refresh = JsonPath.read(body, "$.refreshToken");

        logout(refresh).andExpect(status().isNoContent());

        expectUnauthorized(me(access));
        expectUnauthorized(refresh(refresh));
        expectUnauthorized(logout(refresh));
    }

    @Test
    void deletedUsersTokenIsRejected() throws Exception {
        String access = accessToken(login("kakao", "ok:k1"));

        jdbc.update("DELETE FROM users");

        expectUnauthorized(me(access));
    }

    private ResultActions login(String provider, String credential) throws Exception {
        return mvc.perform(post("/api/v1/auth/login/" + provider)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"credential\":\"" + credential + "\"}"));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"));
    }

    private ResultActions logout(String refreshToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"));
    }

    private ResultActions me(String accessToken) throws Exception {
        return mvc.perform(get("/api/v1/test/me").header("Authorization", "Bearer " + accessToken));
    }

    private String accessToken(ResultActions login) throws Exception {
        return JsonPath.read(login.andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    private String foreignToken() {
        JwtConfig config = new JwtConfig();
        JwtProperties other = new JwtProperties("x".repeat(32));
        return new TokenIssuer(config.jwtEncoder(other), clock).issueAccessToken(1L, UUID.randomUUID());
    }

    private void expectUnauthorized(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew test --no-daemon --tests '*AuthApiTests'`
Expected: 컴파일 실패 (`AuthUser` 없음)

- [ ] **Step 4: 구현**

`auth/AuthUser.java`:

```java
package org.altair.petgyebu_backend_renewal.auth;

public record AuthUser(long userId) {
}
```

`auth/SessionCheckingJwtConverter.java`:

```java
package org.altair.petgyebu_backend_renewal.auth;

import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

// 서명·만료 검증을 통과한 JWT의 세션이 현재 활성 로그인인지 확인한다.
@Component
public class SessionCheckingJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AuthService auth;

    public SessionCheckingJwtConverter(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        long userId;
        UUID sessionId;
        try {
            userId = Long.parseLong(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getClaimAsString("sid"));
        } catch (RuntimeException e) {
            throw new BadCredentialsException("Invalid token claims");
        }
        if (!auth.isActiveSession(userId, sessionId)) {
            throw new BadCredentialsException("Inactive session");
        }
        return UsernamePasswordAuthenticationToken.authenticated(new AuthUser(userId), null, List.of());
    }
}
```

`auth/SecurityConfig.java`:

```java
package org.altair.petgyebu_backend_renewal.auth;

import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    // ApiException.unauthorized()와 같은 본문. 필터 단계 오류는 컨트롤러 예외 처리기를 거치지 않는다.
    static final AuthenticationEntryPoint UNAUTHORIZED_JSON = (request, response, exception) -> {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다.\"}");
    };

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtDecoder jwtDecoder,
            SessionCheckingJwtConverter sessionCheckingJwtConverter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/login/*", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(sessionCheckingJwtConverter))
                        .authenticationEntryPoint(UNAUTHORIZED_JSON))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(UNAUTHORIZED_JSON));
        return http.build();
    }
}
```

`auth/AuthController.java`:

```java
package org.altair.petgyebu_backend_renewal.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    // credential의 의미는 앱인토스 연동 결정 전까지 잠정이다.
    record LoginRequest(@NotBlank String credential) {
    }

    record RefreshTokenRequest(@NotBlank String refreshToken) {
    }

    record TokenResponse(String accessToken, String refreshToken, long accessExpiresInSeconds) {

        static TokenResponse of(AuthService.Tokens tokens) {
            return new TokenResponse(tokens.accessToken(), tokens.refreshToken(),
                    TokenIssuer.ACCESS_TOKEN_TTL.toSeconds());
        }
    }

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login/{provider}")
    TokenResponse login(@PathVariable String provider, @Valid @RequestBody LoginRequest request) {
        Provider parsed = Provider.from(provider)
                .orElseThrow(() -> ApiException.invalidRequest("provider", "kakao 또는 google만 지원합니다."));
        return TokenResponse.of(auth.login(parsed, request.credential()));
    }

    @PostMapping("/refresh")
    TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return TokenResponse.of(auth.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody RefreshTokenRequest request) {
        auth.logout(request.refreshToken());
    }
}
```

`common/ApiExceptionHandler.java`:

```java
package org.altair.petgyebu_backend_renewal.common;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handle(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.code(), e.getMessage(), e.fields()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handle(MethodArgumentNotValidException e) {
        List<ApiError.FieldError> fields = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", "요청 값이 올바르지 않습니다.", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handle(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST", "요청 본문을 읽을 수 없습니다.", null));
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew test --no-daemon --tests '*AuthApiTests'`
Expected: 9 tests PASS.
실패 시 확인할 것: `SessionCheckingJwtConverter`의 `BadCredentialsException`이 `UNAUTHORIZED_JSON` 진입점으로 가는지(500이면 안 됨), `text/plain` 요청이 401로 가려지지 않는지(`/error` 허용).

- [ ] **Step 6: 전체 테스트**

Run: `./gradlew cleanTest test --no-daemon`
Expected: 전체 PASS. 기존 `PetgyebuBackendRenewalApplicationTests`, `FlywayBaselineSchemaTests` 포함.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/altair/petgyebu_backend_renewal/common/ApiExceptionHandler.java \
  src/main/java/org/altair/petgyebu_backend_renewal/auth/AuthController.java \
  src/main/java/org/altair/petgyebu_backend_renewal/auth/AuthUser.java \
  src/main/java/org/altair/petgyebu_backend_renewal/auth/SessionCheckingJwtConverter.java \
  src/main/java/org/altair/petgyebu_backend_renewal/auth/SecurityConfig.java \
  src/test/java/org/altair/petgyebu_backend_renewal/support/TestMeController.java \
  src/test/java/org/altair/petgyebu_backend_renewal/auth/AuthApiTests.java
git commit -m "feat: add auth endpoints and bearer token security"
```

---

### Task 5: 문서 갱신

**Files:**
- Modify: `docs/00-renewal-development-guide.md` (8절 공통 응답 필드·오류)
- Modify: `docs/08-feature-implementation-map.md` (25행 로그인, 68행 오류)
- Modify: `docs/05-infra-stack.md` (25행 인증, 28행 설정·비밀값)
- Modify: `docs/10-task-backlog.md` (`M-003` 검증 기록)
- Add: `docs/superpowers/specs/2026-10-13-m003-auth-session-design.md`, `docs/superpowers/plans/2026-10-13-m003-auth-session.md`

**Interfaces:** 없음 (문서만)

- [ ] **Step 1: `00` 8절 수정**

"로그인과 토큰 갱신 응답은 …" 줄 다음에 추가:

```markdown
- 로그인 요청 본문은 `{credential}`이다. `credential`이 인가 코드인지 공급자 토큰인지는 앱인토스 연동 결정 전까지 잠정이며, 그 전에는 서버가 로그인을 `503 SOCIAL_LOGIN_UNAVAILABLE`로 거부한다. `{provider}`는 `kakao`·`google`이며 대소문자를 구분하지 않는다.
```

"공통 오류 응답은 `code`와 `message`를 가진다." 문장을 포함한 줄 다음에 추가:

```markdown
- 인증 관련 오류 코드: 입력 오류 `400 INVALID_REQUEST`(`fields` 포함), 공급자 확인 실패 `401 SOCIAL_LOGIN_FAILED`, 토큰 없음·무효·만료·종료된 로그인 `401 UNAUTHORIZED`(사유 구분 없음), 공급자 연동 전 `503 SOCIAL_LOGIN_UNAVAILABLE`.
```

- [ ] **Step 2: `08` 수정**

25행 표의 로그인 설명 끝 "공급자별 인가 코드 전달 방식은 잠정"을 "본문 `{credential}`, 의미는 잠정"으로 바꾼다. 68행 공통 실패 응답 문단 끝에 추가:

```markdown
인증 오류 코드는 `INVALID_REQUEST`(400), `SOCIAL_LOGIN_FAILED`(401), `UNAUTHORIZED`(401), `SOCIAL_LOGIN_UNAVAILABLE`(503)이다.
```

- [ ] **Step 3: `05` 수정**

25행 인증 행의 "토큰의 내부 형식이나 추가 라이브러리는 현 단계에서 확정하지 않는다."를 다음으로 바꾼다:

```markdown
액세스 토큰은 HS256 JWT이며 `spring-boot-starter-security-oauth2-resource-server`로 발급·검증한다. 리프레시 토큰은 무작위 값이며 DB에 SHA-256 해시만 저장한다.
```

28행 설정·비밀값 행 끝에 추가:

```markdown
토큰 서명 키는 `PETGYEBU_JWT_SECRET`(32바이트 이상)으로 주입하며 없으면 앱이 시작되지 않는다.
```

- [ ] **Step 4: `10` 검증 기록 추가**

`M-002` 검증 기록 블록 바로 아래에 추가 (상태 체크는 `[ ]` 유지). `<N>`은 Step 5의 실제 테스트 수로 채운다:

```markdown
> `M-003` 진행 기록 (2026-10-13): 로그인·갱신·로그아웃 API, HS256 액세스 토큰(15분, 세션 ID 포함), 해시 저장 리프레시 토큰(마지막 사용 후 30일, 갱신 시 교체), 한 활성 로그인, Bearer 인증 필터와 공통 오류 응답을 구현했다. 공급자 확인은 `SocialIdentityVerifier` 뒤에 있으며 운영 구현은 연동 전까지 `503 SOCIAL_LOGIN_UNAVAILABLE`을 반환한다. 테스트는 가짜 verifier로 검증했다. `./gradlew test --no-daemon` 통과(테스트 <N>개, 실패 0개). 실제 카카오·구글 연동 전이므로 완료 표시하지 않는다.
```

- [ ] **Step 5: 전체 테스트와 수 확인**

Run: `./gradlew cleanTest test --no-daemon && grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/*.xml`
Expected: 모든 suite `failures="0" errors="0"`. 합계를 Step 4의 `<N>`에 넣는다.

- [ ] **Step 6: Commit**

```bash
git add docs/00-renewal-development-guide.md docs/05-infra-stack.md docs/08-feature-implementation-map.md \
  docs/10-task-backlog.md docs/superpowers/specs/2026-10-13-m003-auth-session-design.md \
  docs/superpowers/plans/2026-10-13-m003-auth-session.md
git commit -m "docs: record M-003 auth contract and progress"
```

---

## 실행 후 (부모 에이전트)

1. `superpowers:verification-before-completion`: `./gradlew cleanTest test --no-daemon` 직접 재실행.
2. `qa-engineer` 역할로 브랜치 전체 독립 검토(읽기 전용). 지적은 수정 후 재검토.
3. 사용자 확인 후 push·`develop` 대상 PR.
