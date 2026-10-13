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
