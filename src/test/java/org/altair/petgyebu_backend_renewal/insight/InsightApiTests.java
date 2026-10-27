package org.altair.petgyebu_backend_renewal.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

// TestAuthConfig.START = 2026-10-13T00:00Z = 서울 2026-10-13 09:00 → 현재 월 2026-10
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class InsightApiTests {

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
    void returnsWeeklyAndForecastWithExactShape() throws Exception {
        String a = accessToken("a");
        setBudget(1, "2026-10-01", 300000);
        seedExpense(1, "2026-10-05", 8000, "CAFE");
        seedExpense(1, "2026-10-12", 15000, "CAFE");
        seedExpense(1, "2026-10-13", 6000, "CAFE");
        seedExpense(1, "2026-10-13", 5000, "FOOD");
        seedExpense(1, "2026-10-02", 96000, "FOOD");

        insights(a).andExpect(status().isOk()).andExpect(content().json("""
                {"today":"2026-10-13",
                 "weekly":{"thisWeek":{"from":"2026-10-12","to":"2026-10-13"},"lastWeek":{"from":"2026-10-05","to":"2026-10-06"},
                           "categories":[{"category":"CAFE","thisWeekAmount":21000,"lastWeekAmount":8000,"changeAmount":13000},
                                         {"category":"FOOD","thisWeekAmount":5000,"lastWeekAmount":0,"changeAmount":5000}]},
                 "forecast":{"status":"WILL_EXHAUST","dailyAverageAmount":10000,"exhaustionDate":"2026-10-30"}}
                """, JsonCompareMode.STRICT));
    }

    @Test
    void withoutPetAndBudgetReturnsNoBudget() throws Exception {
        String a = accessToken("a");

        insights(a).andExpect(status().isOk())
                .andExpect(jsonPath("$.forecast.status").value("NO_BUDGET"))
                .andExpect(jsonPath("$.forecast.dailyAverageAmount").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.forecast.exhaustionDate").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.forecast.length()").value(3))
                .andExpect(jsonPath("$.weekly.categories").isEmpty());
    }

    @Test
    void otherUsersRowsAreExcluded() throws Exception {
        String a = accessToken("a");
        accessToken("b");
        setBudget(1, "2026-10-01", 100000);
        seedExpense(1, "2026-10-13", 1000, "FOOD");
        setBudget(2, "2026-10-01", 500);
        seedExpense(2, "2026-10-13", 99000, "CAFE");
        seedExpense(2, "2026-10-05", 7000, "FOOD");

        insights(a).andExpect(status().isOk())
                .andExpect(jsonPath("$.weekly.categories.length()").value(1))
                .andExpect(jsonPath("$.weekly.categories[0].category").value("FOOD"))
                .andExpect(jsonPath("$.weekly.categories[0].thisWeekAmount").value(1000))
                .andExpect(jsonPath("$.weekly.categories[0].lastWeekAmount").value(0))
                .andExpect(jsonPath("$.forecast.status").value("WITHIN_BUDGET"))
                .andExpect(jsonPath("$.forecast.dailyAverageAmount").value(76));
    }

    @Test
    void weekSpanningMonthsUsesPreviousMonthRowsOnlyForWeekly() throws Exception {
        clock.set(Instant.parse("2026-10-31T15:00:00Z"));
        String a = accessToken("a");
        setBudget(1, "2026-11-01", 2500);
        seedExpense(1, "2026-10-26", 1000, "CAFE");
        seedExpense(1, "2026-11-01", 2000, "CAFE");
        seedExpense(1, "2026-10-19", 500, "CAFE");

        insights(a).andExpect(status().isOk())
                .andExpect(jsonPath("$.today").value("2026-11-01"))
                .andExpect(jsonPath("$.weekly.categories.length()").value(1))
                .andExpect(jsonPath("$.weekly.categories[0].thisWeekAmount").value(3000))
                .andExpect(jsonPath("$.weekly.categories[0].lastWeekAmount").value(500))
                .andExpect(jsonPath("$.weekly.categories[0].changeAmount").value(2500))
                .andExpect(jsonPath("$.forecast.status").value("TOO_EARLY"));
    }

    @Test
    void todayFollowsSeoulWhileUtcIsYesterday() throws Exception {
        clock.set(Instant.parse("2026-10-12T15:30:00Z"));
        String a = accessToken("a");

        insights(a).andExpect(status().isOk())
                .andExpect(jsonPath("$.today").value("2026-10-13"))
                .andExpect(jsonPath("$.weekly.thisWeek.from").value("2026-10-12"))
                .andExpect(jsonPath("$.weekly.thisWeek.to").value("2026-10-13"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/insights"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private ResultActions insights(String token) throws Exception {
        return mvc.perform(get("/api/v1/insights").header("Authorization", "Bearer " + token));
    }

    private void setPet(long userId, String petType) {
        jdbc.update("UPDATE users SET pet_type = ? WHERE id = ?", petType, userId);
    }

    private void setBudget(long userId, String month, long amount) {
        jdbc.update("INSERT INTO monthly_budgets (user_id, budget_month, amount) VALUES (?, ?::date, ?)",
                userId, month, amount);
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
}
