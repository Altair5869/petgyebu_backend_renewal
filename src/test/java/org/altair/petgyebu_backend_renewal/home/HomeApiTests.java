package org.altair.petgyebu_backend_renewal.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.altair.petgyebu_backend_renewal.common.ApiException;
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
class HomeApiTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired HomeService homeService;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE expenses, category_goals, monthly_budgets, users RESTART IDENTITY");
        clock.set(TestAuthConfig.START);
    }

    @Test
    void homeReturnsCurrentMonthSummaryOnly() throws Exception {
        String a = accessToken("a");
        setPet(1, "CAT");
        setBudget(1, "2026-10-01", 100000);
        seedExpense(1, "2026-10-13", 12000);

        home(a)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-10"))
                .andExpect(jsonPath("$.petType").value("CAT"))
                .andExpect(jsonPath("$.petState").value("STABLE"))
                .andExpect(jsonPath("$.budgetAmount").value(100000))
                .andExpect(jsonPath("$.spentAmount").value(12000))
                .andExpect(jsonPath("$.usagePercent").value(12.0))
                .andExpect(jsonPath("$.budgetBalanceAmount").value(88000))
                .andExpect(jsonPath("$.*", Matchers.hasSize(7)))
                .andExpect(content().string(Matchers.containsString("\"usagePercent\":12.0")));
    }

    @Test
    void zeroExpensesIsStableWithZeroUsage() throws Exception {
        String a = readyUser("a", 100000);

        expectHome(home(a), 100000, 0, "0.0", "STABLE", 100000);
    }

    @Test
    void acceptanceExamplesForBudgetOneHundredThousand() throws Exception {
        String a = readyUser("a", 100000);

        long[] spents = {30000, 50000, 80000, 100000, 110000};
        String[] percents = {"30.0", "50.0", "80.0", "100.0", "110.0"};
        String[] states = {"STABLE", "CAUTION", "WARNING", "BUDGET_REACHED", "BUDGET_REACHED"};
        for (int i = 0; i < spents.length; i++) {
            replaceExpenses(1, spents[i]);
            expectHome(home(a), 100000, spents[i], percents[i], states[i], 100000 - spents[i]);
        }
    }

    @Test
    void stateUsesExactRatioWhileUsagePercentIsTruncated() throws Exception {
        String a = readyUser("a", 10000);

        // 49.99% → 49.9·STABLE, 79.99% → 79.9·CAUTION, 99.99% → 99.9·WARNING
        long[] spents = {4999, 5000, 7999, 8000, 9999, 10000};
        String[] percents = {"49.9", "50.0", "79.9", "80.0", "99.9", "100.0"};
        String[] states = {"STABLE", "CAUTION", "CAUTION", "WARNING", "WARNING", "BUDGET_REACHED"};
        for (int i = 0; i < spents.length; i++) {
            replaceExpenses(1, spents[i]);
            expectHome(home(a), 10000, spents[i], percents[i], states[i], 10000 - spents[i]);
        }
    }

    @Test
    void truncationNeverRoundsUpAcrossBoundary() throws Exception {
        // 1 / 600,000 = 0.00016…% → 0.0, 299,999 / 600,000 = 49.9998…% → 49.9·STABLE
        String a = readyUser("a", 600000);

        replaceExpenses(1, 299999);
        expectHome(home(a), 600000, 299999, "49.9", "STABLE", 300001);

        replaceExpenses(1, 1);
        expectHome(home(a), 600000, 1, "0.0", "STABLE", 599999);
    }

    @Test
    void largeTotalsDoNotOverflow() throws Exception {
        String a = readyUser("a", 100000000);
        for (int i = 0; i < 25; i++) {
            seedExpense(1, "2026-10-01", 100000000);
        }
        seedExpense(1, "2026-10-13", 33333333);

        // 2,533,333,333 / 100,000,000 × 100 = 2533.333333…%
        expectHome(home(a), 100000000, 2533333333L, "2533.3", "BUDGET_REACHED", -2433333333L);
    }

    @Test
    void missingPetIsPetRequired() throws Exception {
        String a = accessToken("a");
        setBudget(1, "2026-10-01", 100000);

        expectConflict(home(a), "PET_REQUIRED");
    }

    @Test
    void missingCurrentBudgetIsBudgetRequired() throws Exception {
        String a = accessToken("a");
        setPet(1, "DOG");

        expectConflict(home(a), "BUDGET_REQUIRED");
    }

    @Test
    void missingBothIsPetRequiredFirst() throws Exception {
        String a = accessToken("a");

        expectConflict(home(a), "PET_REQUIRED");
    }

    @Test
    void pastMonthBudgetDoesNotSatisfyCurrentMonth() throws Exception {
        String a = accessToken("a");
        setPet(1, "DOG");
        setBudget(1, "2026-09-01", 100000);

        expectConflict(home(a), "BUDGET_REQUIRED");
    }

    @Test
    void onlyCurrentSeoulMonthExpensesCount() throws Exception {
        String a = readyUser("a", 100000);
        seedExpense(1, "2026-09-30", 70000);
        seedExpense(1, "2026-10-01", 10000);
        seedExpense(1, "2026-10-31", 20000);

        expectHome(home(a), 100000, 30000, "30.0", "STABLE", 70000);
    }

    @Test
    void homeMonthFollowsSeoulAtMonthBoundary() throws Exception {
        accessToken("a");
        setPet(1, "CAT");
        setBudget(1, "2026-10-01", 100000);
        setBudget(1, "2026-11-01", 200000);
        seedExpense(1, "2026-10-31", 90000);
        seedExpense(1, "2026-11-01", 50000);

        // 2026-10-31T14:59Z = 서울 2026-10-31 23:59
        clock.set(Instant.parse("2026-10-31T14:59:00Z"));
        String a = accessToken("a");
        home(a).andExpect(status().isOk()).andExpect(jsonPath("$.month").value("2026-10"));
        expectHome(home(a), 100000, 90000, "90.0", "WARNING", 10000);

        // 2026-10-31T15:30Z = 서울 2026-11-01 00:30 (UTC는 아직 10월)
        clock.set(Instant.parse("2026-10-31T15:30:00Z"));
        a = accessToken("a");
        home(a).andExpect(status().isOk()).andExpect(jsonPath("$.month").value("2026-11"));
        expectHome(home(a), 200000, 50000, "25.0", "STABLE", 150000);
    }

    @Test
    void newMonthWithoutBudgetIsBudgetRequired() throws Exception {
        String a = readyUser("a", 100000);
        home(a).andExpect(status().isOk());

        clock.set(Instant.parse("2026-10-31T15:30:00Z"));
        a = accessToken("a");
        expectConflict(home(a), "BUDGET_REQUIRED");
    }

    @Test
    void otherUsersExpensesAndBudgetsAreNotCounted() throws Exception {
        String a = readyUser("a", 100000);
        String b = accessToken("b");
        setPet(2, "DOG");
        setBudget(2, "2026-10-01", 40000);
        seedExpense(1, "2026-10-05", 10000);
        seedExpense(2, "2026-10-05", 30000);
        seedExpense(2, "2026-10-06", 5000);

        expectHome(home(a), 100000, 10000, "10.0", "STABLE", 90000);
        home(a).andExpect(jsonPath("$.petType").value("CAT"));
        expectHome(home(b), 40000, 35000, "87.5", "WARNING", 5000);
        home(b).andExpect(jsonPath("$.petType").value("DOG"));
    }

    @Test
    void homeReflectsExpenseAndBudgetChanges() throws Exception {
        String a = accessToken("a");
        mvc.perform(put("/api/v1/users/me/pet").header("Authorization", "Bearer " + a)
                .contentType(MediaType.APPLICATION_JSON).content("{\"petType\":\"CAT\"}")).andExpect(status().isOk());
        putBudget(a, 100000);
        expectHome(home(a), 100000, 0, "0.0", "STABLE", 100000);

        String first = createExpense(a, 30000, "2026-10-10");
        String second = createExpense(a, 20000, "2026-10-11");
        expectHome(home(a), 100000, 50000, "50.0", "CAUTION", 50000);

        patchExpense(a, first, "{\"amount\":60000}");
        expectHome(home(a), 100000, 80000, "80.0", "WARNING", 20000);

        // 다른 월로 이동하면 현재 월 합계에서 빠진다.
        patchExpense(a, second, "{\"expenseDate\":\"2026-09-30\"}");
        expectHome(home(a), 100000, 60000, "60.0", "CAUTION", 40000);
        patchExpense(a, second, "{\"expenseDate\":\"2026-10-01\"}");
        expectHome(home(a), 100000, 80000, "80.0", "WARNING", 20000);

        // 예산 변경은 합계를 유지하고 사용률·잔액·상태만 다시 계산한다.
        putBudget(a, 80000);
        expectHome(home(a), 80000, 80000, "100.0", "BUDGET_REACHED", 0);
        putBudget(a, 200000);
        expectHome(home(a), 200000, 80000, "40.0", "STABLE", 120000);

        mvc.perform(delete("/api/v1/expenses/" + first).header("Authorization", "Bearer " + a))
                .andExpect(status().isNoContent());
        expectHome(home(a), 200000, 20000, "10.0", "STABLE", 180000);

        mvc.perform(put("/api/v1/users/me/pet").header("Authorization", "Bearer " + a)
                .contentType(MediaType.APPLICATION_JSON).content("{\"petType\":\"DOG\"}")).andExpect(status().isOk());
        home(a).andExpect(jsonPath("$.petType").value("DOG")).andExpect(jsonPath("$.spentAmount").value(20000));
    }

    @Test
    void homeRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/home"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void vanishedUserRowIsUnauthorized() {
        assertThatThrownBy(() -> homeService.home(999L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("UNAUTHORIZED"));
    }

    private String readyUser(String id, long budget) throws Exception {
        String token = accessToken(id);
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE provider_user_id = ?", Long.class, id);
        setPet(userId, "CAT");
        setBudget(userId, "2026-10-01", budget);
        return token;
    }

    private void setPet(long userId, String petType) {
        jdbc.update("UPDATE users SET pet_type = ? WHERE id = ?", petType, userId);
    }

    private void setBudget(long userId, String month, long amount) {
        jdbc.update("INSERT INTO monthly_budgets (user_id, budget_month, amount) VALUES (?, ?::date, ?)",
                userId, month, amount);
    }

    private void seedExpense(long userId, String date, long amount) {
        jdbc.update("INSERT INTO expenses (user_id, amount, expense_date, category) VALUES (?, ?, ?::date, 'FOOD')",
                userId, amount, date);
    }

    private void replaceExpenses(long userId, long amount) {
        jdbc.update("DELETE FROM expenses WHERE user_id = ?", userId);
        seedExpense(userId, "2026-10-13", amount);
    }

    private String accessToken(String id) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"ok:" + id + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private ResultActions home(String token) throws Exception {
        return mvc.perform(get("/api/v1/home").header("Authorization", "Bearer " + token));
    }

    private void putBudget(String token, long amount) throws Exception {
        mvc.perform(put("/api/v1/months/2026-10/budget").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}"))
                .andExpect(status().isOk());
    }

    private String createExpense(String token, long amount, String date) throws Exception {
        String body = mvc.perform(post("/api/v1/expenses").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + ",\"expenseDate\":\"" + date + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return String.valueOf((Integer) JsonPath.read(body, "$.id"));
    }

    private void patchExpense(String token, String id, String json) throws Exception {
        mvc.perform(patch("/api/v1/expenses/" + id).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    // usagePercent는 소수 한 자리 JSON 숫자로 직렬화된다(따옴표 없음).
    private static void expectHome(ResultActions result, long budget, long spent, String usagePercent, String state,
            long balance) throws Exception {
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetAmount").value(budget))
                .andExpect(jsonPath("$.spentAmount").value(spent))
                .andExpect(jsonPath("$.petState").value(state))
                .andExpect(jsonPath("$.budgetBalanceAmount").value(balance))
                .andExpect(content().string(Matchers.anyOf(
                        Matchers.containsString("\"usagePercent\":" + usagePercent + ","),
                        Matchers.containsString("\"usagePercent\":" + usagePercent + "}"))));
    }

    private static void expectConflict(ResultActions result, String code) throws Exception {
        result.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));
    }
}
