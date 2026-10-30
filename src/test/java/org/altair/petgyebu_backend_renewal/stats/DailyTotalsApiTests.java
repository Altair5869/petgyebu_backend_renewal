package org.altair.petgyebu_backend_renewal.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import org.altair.petgyebu_backend_renewal.support.MutableClock;
import org.altair.petgyebu_backend_renewal.support.TestAuthConfig;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// TestAuthConfig.START = 서울 2026-10-13 09:00 → 현재 월 2026-10
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class DailyTotalsApiTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE expenses, category_goals, monthly_budgets, users RESTART IDENTITY");
        clock.set(TestAuthConfig.START);
    }

    @Test
    void returnsOnlyDaysWithSpendingInAscendingOrder() throws Exception {
        String a = accessToken("a");
        seedExpense(1, "2026-10-03", 5000, "FOOD");
        seedExpense(1, "2026-10-01", 12000, "CAFE");
        seedExpense(1, "2026-10-01", 1000, "FOOD");

        dailyTotals(a, "2026-10")
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"month":"2026-10","days":[
                          {"date":"2026-10-01","spentAmount":13000,"expenseCount":2},
                          {"date":"2026-10-03","spentAmount":5000,"expenseCount":1}]}
                        """, JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.days[0].*", Matchers.hasSize(3)));
    }

    @Test
    void emptyMonthReturnsEmptyDays() throws Exception {
        String a = accessToken("a");

        dailyTotals(a, "2026-10")
                .andExpect(status().isOk())
                .andExpect(content().json("{\"month\":\"2026-10\",\"days\":[]}", JsonCompareMode.STRICT));
    }

    @Test
    void includesFirstAndLastDayButNotNeighbourMonths() throws Exception {
        String a = accessToken("a");
        seedExpense(1, "2026-09-30", 100, "FOOD");
        seedExpense(1, "2026-09-01", 200, "FOOD");
        seedExpense(1, "2026-09-15", 300, "FOOD");
        seedExpense(1, "2026-08-31", 400, "FOOD");
        seedExpense(1, "2026-10-01", 500, "FOOD");

        String body = dailyTotals(a, "2026-09").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> dates = JsonPath.read(body, "$.days[*].date");
        assertThat(dates).containsExactly("2026-09-01", "2026-09-15", "2026-09-30");
    }

    @Test
    void daysSumToStatsTotals() throws Exception {
        String a = accessToken("a");
        seedExpense(1, "2026-10-01", 1000, "FOOD");
        seedExpense(1, "2026-10-01", 2500, "CAFE");
        seedExpense(1, "2026-10-07", 4000, "TRANSPORT");
        seedExpense(1, "2026-10-12", 777, "OTHER");
        seedExpense(1, "2026-10-12", 23, "HEALTH");

        expectMatchesStats(a, "2026-10", 8300, 5);
    }

    @Test
    void daysMatchStatsAfterMovingExpenseAcrossMonths() throws Exception {
        String a = accessToken("a");
        String id = createExpense(a, 7000, "2026-10-05", "FOOD");
        createExpense(a, 3000, "2026-10-06", "CAFE");

        patchExpense(a, id, "{\"expenseDate\":\"2026-09-20\"}");

        dailyTotals(a, "2026-10").andExpect(status().isOk())
                .andExpect(jsonPath("$.days[?(@.date=='2026-10-05')]").isEmpty());
        dailyTotals(a, "2026-09").andExpect(status().isOk())
                .andExpect(jsonPath("$.days[?(@.date=='2026-09-20')]").isNotEmpty());
        expectMatchesStats(a, "2026-10", 3000, 1);
        expectMatchesStats(a, "2026-09", 7000, 1);
    }

    @Test
    void excludesOtherUsers() throws Exception {
        String a = accessToken("a");
        accessToken("b");
        seedExpense(1, "2026-10-02", 1000, "FOOD");
        seedExpense(2, "2026-10-02", 9000, "FOOD");
        seedExpense(2, "2026-10-04", 9000, "FOOD");

        dailyTotals(a, "2026-10")
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"month":"2026-10","days":[{"date":"2026-10-02","spentAmount":1000,"expenseCount":1}]}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void currentMonthFollowsSeoulAtMonthBoundary() throws Exception {
        accessToken("a");

        // 2026-10-31T14:30Z = 서울 10-31 23:30 → 11월은 미래
        clock.set(Instant.parse("2026-10-31T14:30:00Z"));
        String a = accessToken("a");
        expectInvalidMonth(dailyTotals(a, "2026-11"));

        // 2026-10-31T15:30Z = 서울 11-01 00:30
        clock.set(Instant.parse("2026-10-31T15:30:00Z"));
        a = accessToken("a");
        dailyTotals(a, "2026-11").andExpect(status().isOk());
    }

    @Test
    void rejectsMalformedFutureAndPre2000Months() throws Exception {
        String a = accessToken("a");
        for (String month : List.of("2026-11", "2026-1", "1999-12")) {
            expectInvalidMonth(dailyTotals(a, month));
        }
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/months/2026-10/daily-totals"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private void expectMatchesStats(String token, String month, long spent, int count) throws Exception {
        String daily = dailyTotals(token, month).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Number> amounts = JsonPath.read(daily, "$.days[*].spentAmount");
        List<Number> counts = JsonPath.read(daily, "$.days[*].expenseCount");
        String stats = mvc.perform(get("/api/v1/months/" + month + "/stats").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(amounts.stream().mapToLong(Number::longValue).sum())
                .isEqualTo(spent).isEqualTo(((Number) JsonPath.read(stats, "$.spentAmount")).longValue());
        assertThat(counts.stream().mapToLong(Number::longValue).sum())
                .isEqualTo(count).isEqualTo(((Number) JsonPath.read(stats, "$.expenseCount")).longValue());
    }

    private void seedExpense(long userId, String date, long amount, String category) {
        jdbc.update("INSERT INTO expenses (user_id, amount, expense_date, category) VALUES (?, ?, ?::date, ?)",
                userId, amount, date, category);
    }

    private String accessToken(String id) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"ok:" + id + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private ResultActions dailyTotals(String token, String month) throws Exception {
        return mvc.perform(get("/api/v1/months/" + month + "/daily-totals").header("Authorization", "Bearer " + token));
    }

    private String createExpense(String token, long amount, String date, String category) throws Exception {
        String body = mvc.perform(post("/api/v1/expenses").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + ",\"expenseDate\":\"" + date + "\",\"category\":\""
                                + category + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return String.valueOf((Integer) JsonPath.read(body, "$.id"));
    }

    private void patchExpense(String token, String id, String json) throws Exception {
        mvc.perform(patch("/api/v1/expenses/" + id).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    private static void expectInvalidMonth(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fields[0].field").value("month"));
    }
}
