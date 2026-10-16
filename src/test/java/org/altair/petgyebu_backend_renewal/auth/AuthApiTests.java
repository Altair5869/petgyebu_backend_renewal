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
        mvc.perform(post("/api/v1/auth/login/kakao").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/auth/login/kakao").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().is4xxClientError())
                .andExpect(status().is(not(401)));
    }

    @Test
    void loginCredentialIsCappedAt4096Characters() throws Exception {
        login("kakao", "x".repeat(4097))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fields[0].field").value("credential"));
        // 4096자는 검증을 통과해 검증기까지 도달한다(가짜 검증기는 "ok:" 아닌 값을 401로 거절).
        login("kakao", "x".repeat(4096))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SOCIAL_LOGIN_FAILED"));
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

    @Test
    void publicAuthEndpointsIgnoreExpiredBearerHeader() throws Exception {
        String body = login("kakao", "ok:k1").andReturn().getResponse().getContentAsString();
        String staleAccess = JsonPath.read(body, "$.accessToken");
        clock.advance(Duration.ofMinutes(16));
        String bearer = "Bearer " + staleAccess;

        mvc.perform(post("/api/v1/auth/login/kakao").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"credential\":\"ok:k1\"}"))
                .andExpect(status().isOk());
        String fresh = JsonPath.read(login("kakao", "ok:k1").andReturn().getResponse().getContentAsString(),
                "$.refreshToken");
        mvc.perform(post("/api/v1/auth/refresh").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"" + fresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isString());
    }

    @Test
    void logoutRejectsForgedUserPrefixWithoutEndingSessions() throws Exception {
        String a = login("kakao", "ok:a").andReturn().getResponse().getContentAsString();
        String b = login("kakao", "ok:b").andReturn().getResponse().getContentAsString();
        String aRefresh = JsonPath.read(a, "$.refreshToken");
        String bRefresh = JsonPath.read(b, "$.refreshToken");

        expectUnauthorized(logout("2" + aRefresh.substring(aRefresh.indexOf('.'))));

        me(JsonPath.read(a, "$.accessToken")).andExpect(status().isOk());
        me(JsonPath.read(b, "$.accessToken")).andExpect(status().isOk());
        logout(aRefresh).andExpect(status().isNoContent());
        logout(bRefresh).andExpect(status().isNoContent());
    }

    @Test
    void logoutRejectsExpiredRefreshToken() throws Exception {
        // 마지막 사용 후 30일 직전까지는 유효하다.
        String beforeExpiry = JsonPath.read(login("kakao", "ok:k1").andReturn().getResponse().getContentAsString(),
                "$.refreshToken");
        clock.advance(Duration.ofDays(30).minusSeconds(1));
        logout(beforeExpiry).andExpect(status().isNoContent());

        // 정확히 30일이 되는 순간부터 만료다.
        String atExpiry = JsonPath.read(login("kakao", "ok:k1").andReturn().getResponse().getContentAsString(),
                "$.refreshToken");
        clock.advance(Duration.ofDays(30));
        expectUnauthorized(logout(atExpiry));
    }

    @Test
    void logoutRejectsRotatedOutRefreshToken() throws Exception {
        String old = JsonPath.read(login("kakao", "ok:k1").andReturn().getResponse().getContentAsString(),
                "$.refreshToken");
        String rotated = JsonPath.read(refresh(old).andReturn().getResponse().getContentAsString(),
                "$.refreshToken");

        expectUnauthorized(logout(old));
        logout(rotated).andExpect(status().isNoContent());
    }

    @Test
    void logoutRejectsRefreshTokenSupersededByNewLogin() throws Exception {
        String first = JsonPath.read(login("kakao", "ok:k1").andReturn().getResponse().getContentAsString(),
                "$.refreshToken");
        String second = accessToken(login("kakao", "ok:k1"));

        expectUnauthorized(logout(first));
        me(second).andExpect(status().isOk());
    }

    @Test
    void logoutRejectsMalformedAndBlankTokens() throws Exception {
        login("kakao", "ok:k1").andExpect(status().isOk());
        for (String token : new String[] {"abc", "1.", ".x", "0.x", "-1.x", "99999999999999999999.x", "1.x"}) {
            expectUnauthorized(logout(token));
        }
        for (String body : new String[] {"{\"refreshToken\":\"\"}", "{\"refreshToken\":\"   \"}", "{}"}) {
            mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.fields[0].field").value("refreshToken"));
        }
    }

    @Test
    void logoutEndsOnlyOwnSession() throws Exception {
        String a = JsonPath.read(login("kakao", "ok:a").andReturn().getResponse().getContentAsString(),
                "$.refreshToken");
        String b = login("kakao", "ok:b").andReturn().getResponse().getContentAsString();

        logout(a).andExpect(status().isNoContent());

        me(JsonPath.read(b, "$.accessToken")).andExpect(status().isOk());
        refresh(JsonPath.read(b, "$.refreshToken")).andExpect(status().isOk());
    }

    @Test
    void logoutIgnoresExpiredBearerHeader() throws Exception {
        String body = login("kakao", "ok:k1").andReturn().getResponse().getContentAsString();
        String staleAccess = JsonPath.read(body, "$.accessToken");
        String refreshToken = JsonPath.read(body, "$.refreshToken");
        clock.advance(Duration.ofMinutes(16));

        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + staleAccess)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isNoContent());
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
