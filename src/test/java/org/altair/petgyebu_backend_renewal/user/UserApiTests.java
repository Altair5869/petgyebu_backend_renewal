package org.altair.petgyebu_backend_renewal.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
class UserApiTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService userService;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE expenses, monthly_budgets, users RESTART IDENTITY");
        clock.set(TestAuthConfig.START);
    }

    @Test
    void meReturnsNullPetTypeBeforeSelection() throws Exception {
        String access = accessToken(login("google", "ok:g1"));

        me(access)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.provider").value("GOOGLE"))
                .andExpect(jsonPath("$.petType").isEmpty())
                .andExpect(jsonPath("$.petType").hasJsonPath());
    }

    @Test
    void meReturnsOnlyCallersInfoWithSelectedPetType() throws Exception {
        login("google", "ok:g1");
        String access = accessToken(login("kakao", "ok:k1"));
        jdbc.update("UPDATE users SET pet_type = 'CAT' WHERE id = 2");

        me(access)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.provider").value("KAKAO"))
                .andExpect(jsonPath("$.petType").value("CAT"));
    }

    @Test
    void meAndWithdrawalRequireAuthentication() throws Exception {
        expectUnauthorized(mvc.perform(get("/api/v1/users/me")));
        expectUnauthorized(mvc.perform(delete("/api/v1/users/me")));
        expectUnauthorized(me("garbage"));
    }

    @Test
    void withdrawalDeletesOnlyCallersRows() throws Exception {
        String access = accessToken(login("kakao", "ok:k1"));
        login("kakao", "ok:other");
        seedRows(1);
        seedRows(2);

        withdraw(access).andExpect(status().isNoContent());

        assertThat(counts(1)).containsExactly(0, 0, 0);
        assertThat(counts(2)).containsExactly(1, 2, 1);
    }

    @Test
    void withdrawalRejectsRemainingTokens() throws Exception {
        String body = login("kakao", "ok:k1").andReturn().getResponse().getContentAsString();
        String access = JsonPath.read(body, "$.accessToken");
        String refresh = JsonPath.read(body, "$.refreshToken");

        withdraw(access).andExpect(status().isNoContent());

        expectUnauthorized(me(access));
        expectUnauthorized(withdraw(access));
        expectUnauthorized(mvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refresh + "\"}")));
    }

    @Test
    void withdrawalRollsBackAllDeletesWhenUserDeleteFails() throws Exception {
        login("kakao", "ok:k1");
        seedRows(1);
        // 지출·예산 삭제 뒤 마지막 단계인 사용자 삭제를 실제 DB에서 실패시킨다.
        jdbc.execute("""
                CREATE FUNCTION fail_user_delete() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced user delete failure'; END $$""");
        jdbc.execute("""
                CREATE TRIGGER fail_user_delete BEFORE DELETE ON users
                FOR EACH ROW EXECUTE FUNCTION fail_user_delete()""");
        try {
            assertThatThrownBy(() -> userService.withdraw(1)).hasMessageContaining("forced user delete failure");
        } finally {
            jdbc.execute("DROP TRIGGER fail_user_delete ON users");
            jdbc.execute("DROP FUNCTION fail_user_delete()");
        }

        assertThat(counts(1)).containsExactly(1, 2, 1);
    }

    private void seedRows(long userId) {
        jdbc.update("INSERT INTO expenses (user_id, amount, expense_date, category) VALUES (?, 1000, '2026-10-01', 'FOOD')",
                userId);
        jdbc.update("INSERT INTO expenses (user_id, amount, expense_date, category) VALUES (?, 2000, '2026-10-02', 'OTHER')",
                userId);
        jdbc.update("INSERT INTO monthly_budgets (user_id, budget_month, amount) VALUES (?, '2026-10-01', 100000)",
                userId);
    }

    // [users, expenses, monthly_budgets] 행 수
    private Integer[] counts(long userId) {
        return new Integer[] {
                jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, userId),
                jdbc.queryForObject("SELECT count(*) FROM expenses WHERE user_id = ?", Integer.class, userId),
                jdbc.queryForObject("SELECT count(*) FROM monthly_budgets WHERE user_id = ?", Integer.class, userId)
        };
    }

    private ResultActions login(String provider, String credential) throws Exception {
        return mvc.perform(post("/api/v1/auth/login/" + provider)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"credential\":\"" + credential + "\"}"));
    }

    private ResultActions me(String accessToken) throws Exception {
        return mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken));
    }

    private ResultActions withdraw(String accessToken) throws Exception {
        return mvc.perform(delete("/api/v1/users/me").header("Authorization", "Bearer " + accessToken));
    }

    private String accessToken(ResultActions login) throws Exception {
        return JsonPath.read(login.andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    private void expectUnauthorized(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
