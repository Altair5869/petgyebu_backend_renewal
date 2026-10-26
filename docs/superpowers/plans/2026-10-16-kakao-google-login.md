# 카카오·구글 로그인 실제 연동 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 운영 `SocialIdentityVerifier`를 503 고정 구현에서 실제 카카오 액세스 토큰·구글 ID 토큰 검증으로 교체한다.

**Architecture:** `auth` 패키지에 공급자별 검증기 두 개(`KakaoTokenVerifier`, `GoogleIdTokenVerifier`)와 이를 묶는 `ProviderSocialIdentityVerifier`를 둔다. 설정은 `SocialLoginProperties`(공개 값만)로 받고, 설정이 빈 공급자는 503. `AuthService.login`은 공급자 확인을 트랜잭션 밖에서 하고 DB 작업만 `TransactionTemplate`으로 감싼다.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring `RestClient`·`RestTemplate`(spring-web), Spring Security `NimbusJwtDecoder`·nimbus-jose-jwt(이미 의존성에 있음), JUnit 5, AssertJ, JDK `com.sun.net.httpserver.HttpServer`(테스트용 가짜 공급자), Testcontainers PostgreSQL.

**Spec:** `docs/superpowers/specs/2026-10-16-kakao-google-login-design.md`

## Global Constraints

- 새 Gradle 의존성을 추가하지 않는다.
- 로그인 API 경로·요청(`{credential}`)·응답 형식은 바꾸지 않는다.
- 카카오 `credential` = SDK 액세스 토큰, 구글 `credential` = ID 토큰.
- 환경 변수: `PETGYEBU_KAKAO_APP_ID`, `PETGYEBU_GOOGLE_CLIENT_IDS`(쉼표 구분). 둘 다 공개 값이며 기본값은 빈 값.
- 카카오 API 기본 주소 `https://kapi.kakao.com`, 구글 JWKS `https://www.googleapis.com/oauth2/v3/certs`.
- 구글 `iss` ∈ {`accounts.google.com`, `https://accounts.google.com`}, RS256.
- 연결 타임아웃 2초, 읽기 타임아웃 3초.
- 토큰 오류 → 401 `SOCIAL_LOGIN_FAILED`(`ApiException.socialLoginFailed()`), 공급자·네트워크·설정 문제 → 503 `SOCIAL_LOGIN_UNAVAILABLE`(`ApiException.socialLoginUnavailable()`).
- 공급자 사용자 ID가 비었거나 255자 초과 → 401.
- 로그에는 공급자·HTTP 상태·예외 종류만 남긴다. 토큰·응답 본문은 남기지 않는다.
- 서버는 공급자 호출을 재시도하지 않는다.
- `M-003` 체크박스는 `[ ]` 유지.
- 테스트 명령: `./gradlew test --no-daemon` (전체 재실행이 필요하면 `cleanTest test`).

## Review Focus

1. 카카오가 200이지만 본문이 JSON이 아니거나 `id`·`app_id`가 빠짐 → 500이 아니라 503이어야 한다 (Task 2에 테스트 포함).
2. 구글 JWKS 서버가 죽었거나 HTML을 돌려줌 → 401이 아니라 503이어야 한다 (Task 3에 테스트 포함).
3. 구글 토큰의 `aud`가 배열이고 그중 하나만 우리 클라이언트 ID → 통과해야 한다 (Task 3에 테스트 포함).
4. `PETGYEBU_GOOGLE_CLIENT_IDS`에 공백·빈 항목(`"a, ,b"`) → 빈 항목은 무시해야 한다 (Task 1에 테스트 포함).
5. 카카오 응답 지연(읽기 타임아웃 초과) → 요청 스레드를 오래 잡지 않고 503 (Task 2에 테스트 포함).

---

## File Structure

| 파일 | 책임 |
|---|---|
| Create `src/main/java/org/altair/petgyebu_backend_renewal/auth/SocialLoginProperties.java` | `petgyebu.social.*` 설정 바인딩, 빈 항목 정리 |
| Create `src/main/java/org/altair/petgyebu_backend_renewal/auth/KakaoTokenVerifier.java` | 카카오 토큰 정보 API 호출·`app_id` 비교 |
| Create `src/main/java/org/altair/petgyebu_backend_renewal/auth/GoogleIdTokenVerifier.java` | 구글 ID 토큰 JWKS 검증 |
| Create `src/main/java/org/altair/petgyebu_backend_renewal/auth/ProviderSocialIdentityVerifier.java` | 공급자별 위임 + 사용자 ID 길이 검증 |
| Create `src/main/java/org/altair/petgyebu_backend_renewal/auth/SocialLoginConfig.java` | 프로퍼티 활성화, 운영 verifier 빈 등록 |
| Delete `src/main/java/org/altair/petgyebu_backend_renewal/auth/UnavailableSocialIdentityVerifier.java` | 대체됨 |
| Delete `src/test/java/org/altair/petgyebu_backend_renewal/auth/UnavailableSocialIdentityVerifierTests.java` | 대체됨 |
| Modify `src/main/java/org/altair/petgyebu_backend_renewal/auth/AuthService.java` | `login`의 트랜잭션 분리 |
| Modify `src/main/resources/application.yaml` | 환경 변수 연결 |
| Create `src/test/java/org/altair/petgyebu_backend_renewal/auth/FakeProviderServer.java` | 테스트용 가짜 HTTP 서버 |
| Create `src/test/java/org/altair/petgyebu_backend_renewal/auth/SocialLoginPropertiesTests.java` | 바인딩 테스트 |
| Create `src/test/java/org/altair/petgyebu_backend_renewal/auth/KakaoTokenVerifierTests.java` | 카카오 단위 테스트 |
| Create `src/test/java/org/altair/petgyebu_backend_renewal/auth/GoogleIdTokenVerifierTests.java` | 구글 단위 테스트 |
| Create `src/test/java/org/altair/petgyebu_backend_renewal/auth/ProviderSocialIdentityVerifierTests.java` | 위임·길이 검증 단위 테스트 |
| Create `src/test/java/org/altair/petgyebu_backend_renewal/auth/SocialLoginApiTests.java` | 실제 verifier + 가짜 공급자로 HTTP 끝단 테스트 |
| Create `src/test/java/org/altair/petgyebu_backend_renewal/auth/AuthServiceTransactionTests.java` | 공급자 확인이 트랜잭션 밖인지 |
| Modify docs `00`·`02`·`05`·`06`·`08`·`09`·`10`, `README.md` | 계약 확정·환경 변수·앱인토스 후속 결정 |

---

### Task 1: 설정 프로퍼티

**Files:**
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/auth/SocialLoginProperties.java`
- Modify: `src/main/resources/application.yaml`
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/auth/SocialLoginPropertiesTests.java`

**Interfaces:**
- Produces: `record SocialLoginProperties(Kakao kakao, Google google)`, `record Kakao(String appId, URI baseUrl)`, `record Google(List<String> clientIds, URI jwkSetUri)`. `kakao().appId()`는 공백 제거 후 빈 값이면 `""`. `google().clientIds()`는 공백 제거·빈 항목 제거된 불변 리스트.

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --no-daemon --tests '*SocialLoginPropertiesTests'`
Expected: FAIL — compilation error, `SocialLoginProperties` does not exist.

- [ ] **Step 3: Write minimal implementation**

`SocialLoginProperties.java`:

```java
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
```

`application.yaml` — append under the existing `petgyebu:` key (keep `jwt` as is):

```yaml
petgyebu:
  jwt:
    secret: ${PETGYEBU_JWT_SECRET:}
  social:
    kakao:
      app-id: ${PETGYEBU_KAKAO_APP_ID:}
    google:
      client-ids: ${PETGYEBU_GOOGLE_CLIENT_IDS:}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --no-daemon --tests '*SocialLoginPropertiesTests'`
Expected: PASS (3 tests). If `client-ids=` binds to `[""]` instead of `[]`, the compact constructor's blank filter covers it — the test asserts the observable result.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/altair/petgyebu_backend_renewal/auth/SocialLoginProperties.java src/main/resources/application.yaml src/test/java/org/altair/petgyebu_backend_renewal/auth/SocialLoginPropertiesTests.java
git commit -m "feat: bind Kakao app ID and Google client IDs for social login"
```

---

### Task 2: 카카오 액세스 토큰 검증

**Files:**
- Create: `src/test/java/org/altair/petgyebu_backend_renewal/auth/FakeProviderServer.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/auth/KakaoTokenVerifier.java`
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/auth/KakaoTokenVerifierTests.java`

**Interfaces:**
- Consumes: `ApiException.socialLoginFailed()`, `ApiException.socialLoginUnavailable()`.
- Produces: `KakaoTokenVerifier(URI baseUrl, String appId, Duration connectTimeout, Duration readTimeout)`, `String verify(String accessToken)` → 카카오 회원번호 문자열. `FakeProviderServer.start()`, `respond(int status, String body)`, `delay(Duration)`, `uri()`, `lastAuthorization()`, `requestCount()`, `lastPath()`, `close()`.

- [ ] **Step 1: Write the test helper**

`FakeProviderServer.java`:

```java
package org.altair.petgyebu_backend_renewal.auth;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

// 카카오 API·구글 JWKS를 흉내 내는 테스트용 HTTP 서버. 경로와 관계없이 설정한 응답을 돌려준다.
final class FakeProviderServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile Duration delay = Duration.ZERO;
    private volatile String lastAuthorization;
    private volatile String lastPath;

    private FakeProviderServer(HttpServer server) {
        this.server = server;
    }

    static FakeProviderServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FakeProviderServer fake = new FakeProviderServer(server);
        server.createContext("/", exchange -> {
            fake.requests.incrementAndGet();
            fake.lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            fake.lastPath = exchange.getRequestURI().getPath();
            try {
                Thread.sleep(fake.delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = fake.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(fake.status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        return fake;
    }

    void respond(int status, String body) {
        this.status = status;
        this.body = body;
    }

    void delay(Duration delay) {
        this.delay = delay;
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    String lastAuthorization() {
        return lastAuthorization;
    }

    String lastPath() {
        return lastPath;
    }

    int requestCount() {
        return requests.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
```

- [ ] **Step 2: Write the failing test**

`KakaoTokenVerifierTests.java`:

```java
package org.altair.petgyebu_backend_renewal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class KakaoTokenVerifierTests {

    private FakeProviderServer kakao;

    @BeforeEach
    void startServer() throws IOException {
        kakao = FakeProviderServer.start();
    }

    @AfterEach
    void stopServer() {
        kakao.close();
    }

    private KakaoTokenVerifier verifier(String appId) {
        return new KakaoTokenVerifier(kakao.uri(), appId, Duration.ofSeconds(2), Duration.ofMillis(500));
    }

    @Test
    void returnsKakaoMemberIdWhenTokenBelongsToOurApp() {
        kakao.respond(200, "{\"id\":4242,\"expires_in\":7199,\"app_id\":123}");

        assertThat(verifier("123").verify("kakao-token")).isEqualTo("4242");
        assertThat(kakao.lastPath()).isEqualTo("/v1/user/access_token_info");
        assertThat(kakao.lastAuthorization()).isEqualTo("Bearer kakao-token");
    }

    @Test
    void rejectsTokenIssuedForAnotherApp() {
        kakao.respond(200, "{\"id\":4242,\"expires_in\":7199,\"app_id\":999}");

        assertFailed(() -> verifier("123").verify("kakao-token"));
    }

    @Test
    void rejectsInvalidOrExpiredToken() {
        kakao.respond(401, "{\"msg\":\"this access token does not exist\",\"code\":-401}");
        assertFailed(() -> verifier("123").verify("bad"));

        kakao.respond(400, "{\"msg\":\"wrong format\",\"code\":-2}");
        assertFailed(() -> verifier("123").verify("bad"));
    }

    @Test
    void providerFailuresAreUnavailable() {
        kakao.respond(500, "{}");
        assertUnavailable(() -> verifier("123").verify("t"));

        kakao.respond(429, "{}");
        assertUnavailable(() -> verifier("123").verify("t"));

        kakao.respond(200, "not json");
        assertUnavailable(() -> verifier("123").verify("t"));

        kakao.respond(200, "{\"expires_in\":7199,\"app_id\":123}");
        assertUnavailable(() -> verifier("123").verify("t"));

        kakao.respond(200, "{\"id\":4242,\"expires_in\":7199}");
        assertUnavailable(() -> verifier("123").verify("t"));
    }

    @Test
    void slowProviderTimesOutAsUnavailable() {
        kakao.respond(200, "{\"id\":4242,\"app_id\":123}");
        kakao.delay(Duration.ofSeconds(2));

        assertUnavailable(() -> verifier("123").verify("t"));
    }

    @Test
    void unconfiguredAppIdIsUnavailableWithoutCallingKakao() {
        assertUnavailable(() -> verifier("").verify("t"));
        assertThat(kakao.requestCount()).isZero();
    }

    private static void assertFailed(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(e.code()).isEqualTo("SOCIAL_LOGIN_FAILED");
        });
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.code()).isEqualTo("SOCIAL_LOGIN_UNAVAILABLE");
        });
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --no-daemon --tests '*KakaoTokenVerifierTests'`
Expected: FAIL — compilation error, `KakaoTokenVerifier` does not exist.

- [ ] **Step 4: Write minimal implementation**

`KakaoTokenVerifier.java`:

```java
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
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --no-daemon --tests '*KakaoTokenVerifierTests'`
Expected: PASS (6 tests). If `rejectsInvalidOrExpiredToken` gets 503 instead of 401, RestClient wrapped the handler's exception — then catch it explicitly: add `catch (ApiException e) { throw e; }` **before** the `RestClientException` catch, or unwrap `e.getCause() instanceof ApiException`; keep whichever the test proves. If `"not json"` is not reported as `RestClientException` (e.g. raw `HttpMessageNotReadableException`), add that type to the 503 catch.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/org/altair/petgyebu_backend_renewal/auth/FakeProviderServer.java src/main/java/org/altair/petgyebu_backend_renewal/auth/KakaoTokenVerifier.java src/test/java/org/altair/petgyebu_backend_renewal/auth/KakaoTokenVerifierTests.java
git commit -m "feat: verify Kakao access tokens via token info API"
```

---

### Task 3: 구글 ID 토큰 검증

**Files:**
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/auth/GoogleIdTokenVerifier.java`
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/auth/GoogleIdTokenVerifierTests.java`

**Interfaces:**
- Consumes: `JwtConfig.notExpired(Clock)` (package-private static, same package), `FakeProviderServer` (Task 2), `ApiException` factories.
- Produces: `GoogleIdTokenVerifier(URI jwkSetUri, List<String> clientIds, Clock clock, Duration connectTimeout, Duration readTimeout)`, `String verify(String idToken)` → 구글 `sub`.

- [ ] **Step 1: Write the failing test**

`GoogleIdTokenVerifierTests.java`:

```java
package org.altair.petgyebu_backend_renewal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GoogleIdTokenVerifierTests {

    private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
    private static final String CLIENT_ID = "ios.apps.googleusercontent.com";

    private FakeProviderServer google;
    private RSAKey key;

    @BeforeEach
    void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        google = FakeProviderServer.start();
        google.respond(200, new JWKSet(key.toPublicJWK()).toString());
    }

    @AfterEach
    void stopServer() {
        google.close();
    }

    private GoogleIdTokenVerifier verifier(List<String> clientIds) {
        return new GoogleIdTokenVerifier(google.uri().resolve("/oauth2/v3/certs"), clientIds,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(2), Duration.ofMillis(500));
    }

    private String token(RSAKey signingKey, String iss, List<String> aud, Instant exp) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(iss)
                .audience(aud)
                .subject("google-sub-1")
                .issueTime(Date.from(NOW.minusSeconds(60)))
                .expirationTime(Date.from(exp))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    @Test
    void returnsSubjectForValidToken() throws Exception {
        String idToken = token(key, "https://accounts.google.com", List.of(CLIENT_ID), NOW.plusSeconds(3600));

        assertThat(verifier(List.of(CLIENT_ID)).verify(idToken)).isEqualTo("google-sub-1");
        assertThat(verifier(List.of(CLIENT_ID)).verify(
                token(key, "accounts.google.com", List.of(CLIENT_ID), NOW.plusSeconds(3600)))).isEqualTo("google-sub-1");
    }

    @Test
    void acceptsWhenOneOfSeveralAudiencesIsOurs() throws Exception {
        String idToken = token(key, "https://accounts.google.com", List.of("other-client", CLIENT_ID),
                NOW.plusSeconds(3600));

        assertThat(verifier(List.of("web-client", CLIENT_ID)).verify(idToken)).isEqualTo("google-sub-1");
    }

    @Test
    void rejectsTokensThatFailValidation() throws Exception {
        RSAKey otherKey = new RSAKeyGenerator(2048).keyID("k1").generate();
        GoogleIdTokenVerifier verifier = verifier(List.of(CLIENT_ID));

        assertFailed(() -> verifier.verify(
                token(otherKey, "https://accounts.google.com", List.of(CLIENT_ID), NOW.plusSeconds(3600))));
        assertFailed(() -> verifier.verify(
                token(key, "https://accounts.google.com", List.of("someone-else"), NOW.plusSeconds(3600))));
        assertFailed(() -> verifier.verify(
                token(key, "https://evil.example.com", List.of(CLIENT_ID), NOW.plusSeconds(3600))));
        assertFailed(() -> verifier.verify(
                token(key, "https://accounts.google.com", List.of(CLIENT_ID), NOW)));
        assertFailed(() -> verifier.verify("not-a-jwt"));
    }

    @Test
    void jwksOutageIsUnavailable() throws Exception {
        String idToken = token(key, "https://accounts.google.com", List.of(CLIENT_ID), NOW.plusSeconds(3600));

        google.respond(500, "{}");
        assertUnavailable(() -> verifier(List.of(CLIENT_ID)).verify(idToken));

        google.respond(200, "<html>oops</html>");
        assertUnavailable(() -> verifier(List.of(CLIENT_ID)).verify(idToken));
    }

    @Test
    void unconfiguredClientIdsAreUnavailableWithoutFetchingKeys() throws Exception {
        String idToken = token(key, "https://accounts.google.com", List.of(CLIENT_ID), NOW.plusSeconds(3600));

        assertUnavailable(() -> verifier(List.of()).verify(idToken));
        assertThat(google.requestCount()).isZero();
    }

    private static void assertFailed(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(e.code()).isEqualTo("SOCIAL_LOGIN_FAILED");
        });
    }

    private static void assertUnavailable(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.code()).isEqualTo("SOCIAL_LOGIN_UNAVAILABLE");
        });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --no-daemon --tests '*GoogleIdTokenVerifierTests'`
Expected: FAIL — compilation error, `GoogleIdTokenVerifier` does not exist.

- [ ] **Step 3: Write minimal implementation**

`GoogleIdTokenVerifier.java`:

```java
package org.altair.petgyebu_backend_renewal.auth;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

// Google Sign-In이 발급한 ID 토큰을 구글 공개키(JWKS)로 검증한다. aud는 우리 클라이언트 ID 중 하나여야 한다.
class GoogleIdTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleIdTokenVerifier.class);
    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final List<String> clientIds;
    private final NimbusJwtDecoder decoder;

    GoogleIdTokenVerifier(URI jwkSetUri, List<String> clientIds, Clock clock, Duration connectTimeout,
            Duration readTimeout) {
        this.clientIds = List.copyOf(clientIds);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri.toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(requestFactory))
                .build();
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtConfig.notExpired(clock),
                new JwtClaimValidator<String>(JwtClaimNames.ISS, ISSUERS::contains),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.stream().anyMatch(this.clientIds::contains))));
    }

    String verify(String idToken) {
        if (clientIds.isEmpty()) {
            throw ApiException.socialLoginUnavailable();
        }
        try {
            return decoder.decode(idToken).getSubject();
        } catch (BadJwtException e) {
            log.warn("Google ID token rejected: {}", e.getClass().getSimpleName());
            throw ApiException.socialLoginFailed();
        } catch (JwtException e) {
            log.warn("Google ID token check unavailable: {}", e.getClass().getSimpleName());
            throw ApiException.socialLoginUnavailable();
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --no-daemon --tests '*GoogleIdTokenVerifierTests'`
Expected: PASS (5 tests). Watch two cases:
- `rejectsTokensThatFailValidation` with `otherKey` (same `kid`, different key) must be 401. If Nimbus reports it as a non-bad `JwtException`, compare the exception message/type and narrow the 503 branch to JWKS retrieval failures only (e.g. cause is `com.nimbusds.jose.RemoteKeySourceException` / `KeySourceException`), treating the rest as 401.
- `jwksOutageIsUnavailable` with HTML body must be 503, not 401 — same rule.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/altair/petgyebu_backend_renewal/auth/GoogleIdTokenVerifier.java src/test/java/org/altair/petgyebu_backend_renewal/auth/GoogleIdTokenVerifierTests.java
git commit -m "feat: verify Google ID tokens against Google JWKS"
```

---

### Task 4: 운영 verifier 교체와 HTTP 끝단 확인

**Files:**
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/auth/ProviderSocialIdentityVerifier.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/auth/SocialLoginConfig.java`
- Delete: `src/main/java/org/altair/petgyebu_backend_renewal/auth/UnavailableSocialIdentityVerifier.java`
- Delete: `src/test/java/org/altair/petgyebu_backend_renewal/auth/UnavailableSocialIdentityVerifierTests.java`
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/auth/ProviderSocialIdentityVerifierTests.java`
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/auth/SocialLoginApiTests.java`

**Interfaces:**
- Consumes: `SocialLoginProperties` (Task 1), `KakaoTokenVerifier` (Task 2), `GoogleIdTokenVerifier` (Task 3), `SocialIdentityVerifier`, `Provider`.
- Produces: `ProviderSocialIdentityVerifier(KakaoTokenVerifier kakao, GoogleIdTokenVerifier google)` implementing `SocialIdentityVerifier`; `static final int MAX_PROVIDER_USER_ID_LENGTH = 255`. `SocialLoginConfig` bean `SocialIdentityVerifier socialIdentityVerifier(SocialLoginProperties, Clock)` with `CONNECT_TIMEOUT = 2s`, `READ_TIMEOUT = 3s`.

- [ ] **Step 1: Write the failing unit test**

`ProviderSocialIdentityVerifierTests.java`:

```java
package org.altair.petgyebu_backend_renewal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ProviderSocialIdentityVerifierTests {

    private FakeProviderServer kakao;
    private ProviderSocialIdentityVerifier verifier;

    @BeforeEach
    void setUp() throws IOException {
        kakao = FakeProviderServer.start();
        verifier = new ProviderSocialIdentityVerifier(
                new KakaoTokenVerifier(kakao.uri(), "123", Duration.ofSeconds(2), Duration.ofSeconds(2)),
                new GoogleIdTokenVerifier(kakao.uri(), List.of(), Clock.systemUTC(), Duration.ofSeconds(2),
                        Duration.ofSeconds(2)));
    }

    @AfterEach
    void stop() {
        kakao.close();
    }

    @Test
    void delegatesKakaoToKakaoVerifier() {
        kakao.respond(200, "{\"id\":4242,\"app_id\":123}");

        assertThat(verifier.verify(Provider.KAKAO, "t")).isEqualTo("4242");
    }

    @Test
    void delegatesGoogleToGoogleVerifier() {
        // client IDs are empty → Google verifier reports unavailable without network
        assertThatThrownBy(() -> verifier.verify(Provider.GOOGLE, "t"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void rejectsProviderUserIdLongerThanColumn() {
        String tooLong = "9".repeat(ProviderSocialIdentityVerifier.MAX_PROVIDER_USER_ID_LENGTH + 1);
        kakao.respond(200, "{\"id\":" + tooLong + ",\"app_id\":123}");

        assertThatThrownBy(() -> verifier.verify(Provider.KAKAO, "t"))
                .isInstanceOf(ApiException.class);
    }
}
```

Note: a 256-digit `id` overflows `Long` in `KakaoTokenVerifier.TokenInfo`, so Kakao parsing fails first (503) — the assertion only requires an `ApiException`. The length rule itself is pinned by the next step's direct test.

Add to the same class:

```java
    @Test
    void lengthRuleRejectsBlankAndOverlongIds() {
        SocialIdentityVerifier blank = ProviderSocialIdentityVerifier.requireStorable((p, c) -> " ");
        SocialIdentityVerifier overlong = ProviderSocialIdentityVerifier.requireStorable((p, c) -> "a".repeat(256));
        SocialIdentityVerifier max = ProviderSocialIdentityVerifier.requireStorable((p, c) -> "a".repeat(255));

        assertThatThrownBy(() -> blank.verify(Provider.KAKAO, "t")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("SOCIAL_LOGIN_FAILED"));
        assertThatThrownBy(() -> overlong.verify(Provider.GOOGLE, "t")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("SOCIAL_LOGIN_FAILED"));
        assertThat(max.verify(Provider.KAKAO, "t")).hasSize(255);
    }
```

- [ ] **Step 2: Write the failing API test**

`SocialLoginApiTests.java` (real verifiers, fake provider; does NOT import `TestAuthConfig`):

```java
package org.altair.petgyebu_backend_renewal.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class SocialLoginApiTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static final FakeProviderServer kakao;

    static {
        try {
            kakao = FakeProviderServer.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("petgyebu.social.kakao.app-id", () -> "123");
        registry.add("petgyebu.social.kakao.base-url", () -> kakao.uri().toString());
        registry.add("petgyebu.social.google.client-ids", () -> "");
    }

    @AfterAll
    static void stop() {
        kakao.close();
    }

    @Autowired MockMvc mvc;

    @Test
    void kakaoLoginIssuesOurTokens() throws Exception {
        kakao.respond(200, "{\"id\":4242,\"app_id\":123}");

        login("kakao", "kakao-access-token")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString());
    }

    @Test
    void invalidKakaoTokenIs401() throws Exception {
        kakao.respond(401, "{\"code\":-401}");

        login("kakao", "bad").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SOCIAL_LOGIN_FAILED"));
    }

    @Test
    void kakaoOutageIs503() throws Exception {
        kakao.respond(503, "{}");

        login("kakao", "t").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOCIAL_LOGIN_UNAVAILABLE"));
    }

    @Test
    void unconfiguredGoogleIs503() throws Exception {
        login("google", "id-token").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOCIAL_LOGIN_UNAVAILABLE"));
    }

    private ResultActions login(String provider, String credential) throws Exception {
        return mvc.perform(post("/api/v1/auth/login/" + provider)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"credential\":\"" + credential + "\"}"));
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew test --no-daemon --tests '*ProviderSocialIdentityVerifierTests' --tests '*SocialLoginApiTests'`
Expected: FAIL — compilation error, `ProviderSocialIdentityVerifier` does not exist.

- [ ] **Step 4: Write the implementation and remove the 503 stub**

`ProviderSocialIdentityVerifier.java`:

```java
package org.altair.petgyebu_backend_renewal.auth;

import org.altair.petgyebu_backend_renewal.common.ApiException;

// 운영 공급자 확인: 카카오 액세스 토큰, 구글 ID 토큰을 검증하고 저장 가능한 사용자 ID만 돌려준다.
public class ProviderSocialIdentityVerifier implements SocialIdentityVerifier {

    static final int MAX_PROVIDER_USER_ID_LENGTH = 255;

    private final SocialIdentityVerifier delegate;

    ProviderSocialIdentityVerifier(KakaoTokenVerifier kakao, GoogleIdTokenVerifier google) {
        this.delegate = requireStorable((provider, credential) -> switch (provider) {
            case KAKAO -> kakao.verify(credential);
            case GOOGLE -> google.verify(credential);
        });
    }

    @Override
    public String verify(Provider provider, String credential) {
        return delegate.verify(provider, credential);
    }

    // provider_user_id는 VARCHAR(255)다. 비었거나 더 긴 ID는 저장할 수 없으므로 로그인 실패로 본다.
    static SocialIdentityVerifier requireStorable(SocialIdentityVerifier verifier) {
        return (provider, credential) -> {
            String id = verifier.verify(provider, credential);
            if (id == null || id.isBlank() || id.length() > MAX_PROVIDER_USER_ID_LENGTH) {
                throw ApiException.socialLoginFailed();
            }
            return id;
        };
    }
}
```

`SocialLoginConfig.java`:

```java
package org.altair.petgyebu_backend_renewal.auth;

import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SocialLoginProperties.class)
public class SocialLoginConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    SocialIdentityVerifier socialIdentityVerifier(SocialLoginProperties properties, Clock clock) {
        return new ProviderSocialIdentityVerifier(
                new KakaoTokenVerifier(properties.kakao().baseUrl(), properties.kakao().appId(),
                        CONNECT_TIMEOUT, READ_TIMEOUT),
                new GoogleIdTokenVerifier(properties.google().jwkSetUri(), properties.google().clientIds(), clock,
                        CONNECT_TIMEOUT, READ_TIMEOUT));
    }
}
```

Delete the stub and its test:

```bash
git rm src/main/java/org/altair/petgyebu_backend_renewal/auth/UnavailableSocialIdentityVerifier.java src/test/java/org/altair/petgyebu_backend_renewal/auth/UnavailableSocialIdentityVerifierTests.java
```

`TestAuthConfig` and `LocalDevApplication.LocalDevConfig` declare `@Primary SocialIdentityVerifier` beans, so they still override this bean — no change there.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --no-daemon --tests '*ProviderSocialIdentityVerifierTests' --tests '*SocialLoginApiTests' --tests '*AuthApiTests'`
Expected: PASS. `AuthApiTests` still uses the fake verifier via `@Primary`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/altair/petgyebu_backend_renewal/auth/ProviderSocialIdentityVerifier.java src/main/java/org/altair/petgyebu_backend_renewal/auth/SocialLoginConfig.java src/test/java/org/altair/petgyebu_backend_renewal/auth/ProviderSocialIdentityVerifierTests.java src/test/java/org/altair/petgyebu_backend_renewal/auth/SocialLoginApiTests.java
git commit -m "feat: replace 503 stub with Kakao/Google social identity verification"
```

---

### Task 5: 공급자 확인을 DB 트랜잭션 밖으로

**Files:**
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/auth/AuthService.java` (`login`, constructor)
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/auth/AuthServiceTransactionTests.java`

**Interfaces:**
- Consumes: `SocialIdentityVerifier`, Spring Boot auto-configured `TransactionTemplate` (already used by `user/UserService`).
- Produces: `AuthService(SocialIdentityVerifier, UserRepository, TokenIssuer, Clock, TransactionTemplate)`; `login` keeps signature `Tokens login(Provider, String)`.

- [ ] **Step 1: Write the failing test**

`AuthServiceTransactionTests.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --no-daemon --tests '*AuthServiceTransactionTests'`
Expected: FAIL — `expected false but was true` (current `login` is `@Transactional`).

- [ ] **Step 3: Write minimal implementation**

In `AuthService.java`: add field `private final TransactionTemplate tx;`, add the constructor parameter `TransactionTemplate tx` (last), import `org.springframework.transaction.support.TransactionTemplate`, and replace `login`:

```java
    // 공급자 확인은 외부 HTTP 호출이므로 DB 트랜잭션 밖에서 하고, 확인된 뒤에만 짧게 저장한다.
    public Tokens login(Provider provider, String credential) {
        String providerUserId = verifier.verify(provider, credential);
        return tx.execute(status -> startSession(provider, providerUserId));
    }

    private Tokens startSession(Provider provider, String providerUserId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long userId = users.upsertForLogin(provider.name(), providerUserId, now);

        UUID sessionId = UUID.randomUUID();
        String refreshToken = tokens.newRefreshToken(userId);
        users.startSession(userId, sessionId, tokens.hash(refreshToken), now.plus(TokenIssuer.REFRESH_TOKEN_TTL));
        return new Tokens(tokens.issueAccessToken(userId, sessionId), refreshToken);
    }
```

Remove the `@Transactional` annotation that was on `login` only; keep it on `refresh`, `logout`, `isActiveSession`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --no-daemon --tests '*AuthServiceTransactionTests' --tests '*AuthServiceTests' --tests '*AuthApiTests'`
Expected: PASS — including existing concurrency/login tests in `AuthServiceTests`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/altair/petgyebu_backend_renewal/auth/AuthService.java src/test/java/org/altair/petgyebu_backend_renewal/auth/AuthServiceTransactionTests.java
git commit -m "fix: verify social identity outside the login DB transaction"
```

---

### Task 6: 문서 확정과 전체 검증

**Files:**
- Modify: `docs/00-renewal-development-guide.md` (lines ~110, ~137, ~171)
- Modify: `docs/02-requirements-features.md` (line ~21)
- Modify: `docs/05-infra-stack.md` (설정·비밀값 row ~28, line ~35, §6 ~69)
- Modify: `docs/06-sprint-plan.md` (line ~61)
- Modify: `docs/08-feature-implementation-map.md` (row 1 ~25, line ~49)
- Modify: `docs/10-task-backlog.md` (M-003 진행 기록 뒤 한 줄, 체크박스는 `[ ]` 유지)
- Modify: `README.md` (로컬 실행 절)

- [ ] **Step 1: Replace the provisional wording**

`docs/00-renewal-development-guide.md`:
- ~110: replace `로그인 경로의 공급자별 인가 코드 전달 방식은 앱인토스 연동 결정 전까지 잠정이다.` with `로그인 요청의 credential은 카카오 SDK 액세스 토큰·구글 ID 토큰이다(스토어 앱 기준).`
- ~137: replace `` `credential`이 인가 코드인지 공급자 토큰인지는 앱인토스 연동 결정 전까지 잠정이며, 그 전에는 서버가 로그인을 `503 SOCIAL_LOGIN_UNAVAILABLE`로 거부한다. `` with `` `credential`은 카카오는 SDK가 발급한 액세스 토큰, 구글은 Google Sign-In의 ID 토큰이다. 서버는 카카오 토큰 정보 API(`app_id` 일치)와 구글 JWKS(서명·`iss`·`aud`·`exp`)로 확인한다. 토큰이 틀리면 `401 SOCIAL_LOGIN_FAILED`, 공급자 장애·응답 이상·해당 공급자 설정 누락이면 `503 SOCIAL_LOGIN_UNAVAILABLE`이다. ``
- ~171 (후속 결정 표): replace the row with `| 앱인토스 로그인 | 앱인토스 미니앱은 토스 로그인만 허용한다(2026-10-16 확인, [토스 로그인 소개](https://developers-apps-in-toss.toss.im/login/intro.html)). 앱인토스 출시 때 공급자 TOSS(인가 코드 교환·mTLS·연결 끊기 콜백)를 별도로 설계한다. 카카오·구글은 스토어 앱용이다. |`

`docs/02-requirements-features.md` ~21: replace `공급자별 인가 코드 전달 방식은 앱인토스 연동 확정 전까지 잠정이다.` with `앱은 카카오 SDK 액세스 토큰 또는 구글 ID 토큰을 credential로 보낸다. 앱인토스 미니앱은 토스 로그인만 허용하므로 별도 후속 작업이다.`

`docs/05-infra-stack.md`:
- 설정·비밀값 row: append `카카오 앱 ID(PETGYEBU_KAKAO_APP_ID)와 구글 클라이언트 ID 목록(PETGYEBU_GOOGLE_CLIENT_IDS, 쉼표 구분)은 공개 값이며 비어 있으면 해당 공급자 로그인이 503이다.`
- ~35: replace `공급자별 인가 코드 전달 방식과 앱인토스 연동 상세는 출시 플랫폼 절차를 확인한 뒤 정한다.` with `카카오 액세스 토큰은 토큰 정보 API로, 구글 ID 토큰은 JWKS로 확인하며 연결 2초·읽기 3초 타임아웃을 둔다. 앱인토스용 토스 로그인은 후속 작업이다.`
- §6 ~69: replace `앱인토스에 맞춘 카카오·구글 인증 연동 상세와` with `앱인토스용 토스 로그인과`

`docs/06-sprint-plan.md` ~61: replace `앱인토스의 인가 코드 전달 방식은 현재 잠정 계약을 검증한 뒤 맞춘다.` with `앱인토스는 토스 로그인만 허용하므로 별도 후속 작업으로 다룬다.`

`docs/08-feature-implementation-map.md`:
- row 1: replace `본문 `{credential}`, 의미는 잠정` with ``본문 `{credential}` — 카카오 액세스 토큰·구글 ID 토큰``
- ~49: replace `앱인토스에 맞춘 공급자별 인증 전달 상세는 후속 결정이다.` with `카카오 토큰은 토큰 정보 API의 app_id 일치로, 구글 ID 토큰은 JWKS 서명·iss·aud·exp로 확인한다. 앱인토스용 토스 로그인은 후속 결정이다.`

`docs/10-task-backlog.md`: after the `M-003` 진행 기록 blockquote add:
`> `M-003` 후속 기록 (2026-10-16): 운영 확인기를 카카오 토큰 정보 API(app_id 일치)·구글 JWKS 검증으로 교체하고 공급자 확인을 로그인 DB 트랜잭션 밖으로 옮겼다. 가짜 공급자 서버로 정상·토큰 오류(401)·공급자 장애(503)·설정 누락(503)을 검증했다. 실제 개발자 콘솔 키로 앱에서 확인하는 일은 M-010에 남아 있어 완료 표시하지 않는다.`

`README.md` 로컬 실행 절: add one line — `` 운영 로그인 확인에는 `PETGYEBU_KAKAO_APP_ID`, `PETGYEBU_GOOGLE_CLIENT_IDS`(공개 값)가 필요하다. 비어 있으면 해당 공급자 로그인은 503이다. ``

Before editing, grep each sentence to confirm it exists verbatim (`grep -n "잠정" docs/0*.md`); if wording drifted, edit the equivalent sentence with the same meaning and report the difference.

- [ ] **Step 2: Verify no provisional wording remains about credential**

Run: `grep -n "잠정" docs/00-renewal-development-guide.md docs/02-requirements-features.md docs/05-infra-stack.md docs/06-sprint-plan.md docs/08-feature-implementation-map.md`
Expected: no line about `credential`/인가 코드 전달 (the `/auth/login/{provider}` 잠정 경로 mention in 00 ~171 is replaced too).

- [ ] **Step 3: Run the full suite**

Run: `./gradlew cleanTest test --no-daemon`
Expected: BUILD SUCCESSFUL, all tests pass (previous 154 − 1 removed + new ones).

- [ ] **Step 4: Commit**

```bash
git add docs/00-renewal-development-guide.md docs/02-requirements-features.md docs/05-infra-stack.md docs/06-sprint-plan.md docs/08-feature-implementation-map.md docs/10-task-backlog.md README.md
git commit -m "docs: fix Kakao/Google login credential contract and defer Toss login"
```
