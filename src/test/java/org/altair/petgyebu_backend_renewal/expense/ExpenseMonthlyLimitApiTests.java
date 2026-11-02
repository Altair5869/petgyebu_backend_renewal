package org.altair.petgyebu_backend_renewal.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Collections;
import java.util.Map;
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

// 사용자별 expenseDate 월의 지출 건수 상한(1,000건). TestAuthConfig.START = 서울 2026-10-13 09:00
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class ExpenseMonthlyLimitApiTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final int LIMIT = 1000;

    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE expenses, category_goals, monthly_budgets, users RESTART IDENTITY");
        clock.set(TestAuthConfig.START);
    }

    @Test
    void createIsAllowedUpTo1000thExpenseThen409() throws Exception {
        String a = accessToken("a");
        seed(1, "2026-10-01", LIMIT - 1);

        create(a, body("2026-10-13"), null).andExpect(status().isCreated());
        assertThat(count(1, "2026-10-01")).isEqualTo(LIMIT);

        create(a, body("2026-10-01"), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONTHLY_EXPENSE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message").value("한 달에 기록할 수 있는 지출은 최대 1,000건입니다."));
        assertThat(count(1, "2026-10-01")).isEqualTo(LIMIT);
    }

    @Test
    void limitIsPerMonthOfExpenseDate() throws Exception {
        String a = accessToken("a");
        seed(1, "2026-10-01", LIMIT);

        create(a, body("2026-09-30"), null).andExpect(status().isCreated());
        create(a, body("2026-10-01"), null).andExpect(status().isConflict());
        assertThat(count(1, "2026-09-01")).isEqualTo(1);
    }

    @Test
    void limitIsPerUser() throws Exception {
        accessToken("a");
        String b = accessToken("b");
        seed(1, "2026-10-01", LIMIT);

        create(b, body("2026-10-13"), null).andExpect(status().isCreated());
        assertThat(count(2, "2026-10-01")).isEqualTo(1);
    }

    @Test
    void idempotentReplayStillSucceedsInFullMonth() throws Exception {
        String a = accessToken("a");
        seed(1, "2026-10-01", LIMIT - 1);
        String key = UUID.randomUUID().toString();

        String first = create(a, body("2026-10-13"), key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String replay = create(a, body("2026-10-13"), key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(count(1, "2026-10-01")).isEqualTo(LIMIT);
    }

    @Test
    void patchMovingIntoFullMonthIs409AndRowIsUnchanged() throws Exception {
        String a = accessToken("a");
        long id = id(create(a, body("2026-09-15"), null).andExpect(status().isCreated()));
        seed(1, "2026-10-01", LIMIT);
        Map<String, Object> before = row(id);

        update(a, id, "{\"expenseDate\":\"2026-10-02\",\"amount\":5000}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONTHLY_EXPENSE_LIMIT_EXCEEDED"));

        assertThat(row(id)).isEqualTo(before);
        assertThat(count(1, "2026-10-01")).isEqualTo(LIMIT);
    }

    @Test
    void patchWithinFullMonthIsAllowed() throws Exception {
        String a = accessToken("a");
        seed(1, "2026-10-01", LIMIT);
        long id = jdbc.queryForObject("SELECT max(id) FROM expenses", Long.class);

        update(a, id, "{\"amount\":7000,\"expenseDate\":\"2026-10-13\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(7000));
    }

    @Test
    void patchMovingOutOfFullMonthOrIntoNonFullMonthIsAllowed() throws Exception {
        String a = accessToken("a");
        seed(1, "2026-10-01", LIMIT);
        long octId = jdbc.queryForObject("SELECT max(id) FROM expenses", Long.class);
        seed(1, "2026-08-01", LIMIT - 1);
        long sepId = id(create(a, body("2026-09-15"), null).andExpect(status().isCreated()));

        // 가득 찬 10월에서 9월로
        update(a, octId, "{\"expenseDate\":\"2026-09-01\"}").andExpect(status().isOk());
        // 999건인 8월로
        update(a, sepId, "{\"expenseDate\":\"2026-08-31\"}").andExpect(status().isOk());

        assertThat(count(1, "2026-10-01")).isEqualTo(LIMIT - 1);
        assertThat(count(1, "2026-08-01")).isEqualTo(LIMIT);
    }

    private void seed(long userId, String date, int rows) {
        jdbc.batchUpdate("INSERT INTO expenses (user_id, amount, expense_date, category) VALUES (?, 1000, ?::date, 'FOOD')",
                Collections.nCopies(rows, new Object[] {userId, date}));
    }

    // monthStart가 속한 달의 그 사용자 지출 건수
    private int count(long userId, String monthStart) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM expenses
                WHERE user_id = ? AND expense_date >= ?::date AND expense_date < ?::date + interval '1 month'
                """, Integer.class, userId, monthStart, monthStart);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT amount, expense_date, updated_at FROM expenses WHERE id = ?", id);
    }

    private static long id(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private static String body(String date) {
        return "{\"amount\":1000,\"expenseDate\":\"" + date + "\",\"category\":\"FOOD\"}";
    }

    private String accessToken(String id) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"ok:" + id + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private ResultActions create(String token, String json, String key) throws Exception {
        var request = post("/api/v1/expenses").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private ResultActions update(String token, long id, String json) throws Exception {
        return mvc.perform(patch("/api/v1/expenses/" + id).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
