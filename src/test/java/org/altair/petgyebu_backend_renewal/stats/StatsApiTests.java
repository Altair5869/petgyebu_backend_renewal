package org.altair.petgyebu_backend_renewal.stats;

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
class StatsApiTests {

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
    void currentMonthReturnsExactFieldsWithoutPet() throws Exception {
        String a = accessToken("a");
        setPet(1, "CAT");
        setBudget(1, "2026-10-01", 100000);
        seedExpense(1, "2026-10-02", 30000, "FOOD");
        seedExpense(1, "2026-10-03", 10000, "CAFE");
        seedExpense(1, "2026-10-04", 5000, "FOOD");

        stats(a, "2026-10")
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"month":"2026-10","budgetAmount":100000,"spentAmount":45000,"expenseCount":3,
                         "usagePercent":45.0,"budgetBalanceAmount":55000,
                         "categories":[{"category":"FOOD","spentAmount":35000,"expenseCount":2,"sharePercent":77.8},
                                       {"category":"CAFE","spentAmount":10000,"expenseCount":1,"sharePercent":22.2}]}
                        """, JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.*", Matchers.hasSize(7)))
                .andExpect(jsonPath("$.categories[0].*", Matchers.hasSize(4)))
                .andExpect(jsonPath("$.petType").doesNotExist())
                .andExpect(jsonPath("$.petState").doesNotExist())
                .andExpect(content().string(Matchers.containsString("\"usagePercent\":45.0,")));
    }

    @Test
    void pastMonthWithBudgetReturnsBudgetFieldsWithoutPet() throws Exception {
        String a = accessToken("a");
        setPet(1, "DOG");
        setBudget(1, "2026-09-01", 50000);
        seedExpense(1, "2026-09-10", 60000, "HEALTH");

        stats(a, "2026-09")
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"month":"2026-09","budgetAmount":50000,"spentAmount":60000,"expenseCount":1,
                         "usagePercent":120.0,"budgetBalanceAmount":-10000,
                         "categories":[{"category":"HEALTH","spentAmount":60000,"expenseCount":1,"sharePercent":100.0}]}
                        """, JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.petType").doesNotExist())
                .andExpect(jsonPath("$.petState").doesNotExist())
                .andExpect(content().string(Matchers.containsString("\"sharePercent\":100.0}")));
    }

    @Test
    void pastMonthWithoutBudgetHasNullBudgetFieldsOnly() throws Exception {
        String a = accessToken("a");
        setBudget(1, "2026-10-01", 100000);
        seedExpense(1, "2026-08-31", 7000, "LIVING");

        stats(a, "2026-08")
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"month":"2026-08","budgetAmount":null,"spentAmount":7000,"expenseCount":1,
                         "usagePercent":null,"budgetBalanceAmount":null,
                         "categories":[{"category":"LIVING","spentAmount":7000,"expenseCount":1,"sharePercent":100.0}]}
                        """, JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.*", Matchers.hasSize(7)));
    }

    // 현재 월도 예산이 없으면 409가 아니라 200과 예산 관련 null이다. 펫도 요구하지 않는다.
    @Test
    void currentMonthWithoutBudgetHasNullBudgetFields() throws Exception {
        String a = accessToken("a");
        seedExpense(1, "2026-10-05", 3000, "OTHER");

        stats(a, "2026-10")
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"month":"2026-10","budgetAmount":null,"spentAmount":3000,"expenseCount":1,
                         "usagePercent":null,"budgetBalanceAmount":null,
                         "categories":[{"category":"OTHER","spentAmount":3000,"expenseCount":1,"sharePercent":100.0}]}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void emptyCurrentMonthIsZeroWithEmptyCategories() throws Exception {
        String a = accessToken("a");
        setBudget(1, "2026-10-01", 100000);

        stats(a, "2026-10")
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"month":"2026-10","budgetAmount":100000,"spentAmount":0,"expenseCount":0,
                         "usagePercent":0.0,"budgetBalanceAmount":100000,"categories":[]}
                        """, JsonCompareMode.STRICT))
                .andExpect(content().string(Matchers.containsString("\"usagePercent\":0.0,")));
    }

    @Test
    void emptyPastMonthsAreZeroWithEmptyCategories() throws Exception {
        String a = accessToken("a");
        setBudget(1, "2026-09-01", 80000);

        stats(a, "2026-09")
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"month":"2026-09","budgetAmount":80000,"spentAmount":0,"expenseCount":0,
                         "usagePercent":0.0,"budgetBalanceAmount":80000,"categories":[]}
                        """, JsonCompareMode.STRICT));
        stats(a, "2000-01")
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"month":"2000-01","budgetAmount":null,"spentAmount":0,"expenseCount":0,
                         "usagePercent":null,"budgetBalanceAmount":null,"categories":[]}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void categoriesSortByAmountDescThenFixedCodeOrder() throws Exception {
        String a = accessToken("a");
        // 코드 순서와 반대로 넣어 ID·삽입 순서에 기대지 않는지 확인한다.
        seedExpense(1, "2026-10-01", 1000, "OTHER");
        seedExpense(1, "2026-10-02", 1000, "LIVING");
        seedExpense(1, "2026-10-03", 1000, "CAFE");
        seedExpense(1, "2026-10-04", 500, "FOOD");
        seedExpense(1, "2026-10-05", 500, "FOOD");
        seedExpense(1, "2026-10-06", 3000, "SHOPPING");
        seedExpense(1, "2026-10-07", 200, "TRANSPORT");
        seedExpense(1, "2026-10-08", 300, "HEALTH");

        stats(a, "2026-10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spentAmount").value(7500))
                .andExpect(jsonPath("$.expenseCount").value(8))
                .andExpect(jsonPath("$.categories[*].category").value(Matchers.contains(
                        "SHOPPING", "FOOD", "CAFE", "LIVING", "OTHER", "HEALTH", "TRANSPORT")))
                .andExpect(jsonPath("$.categories[1].expenseCount").value(2))
                .andExpect(jsonPath("$.categories[1].spentAmount").value(1000));
    }

    @Test
    void sharePercentRoundsHalfUpToOneDecimal() throws Exception {
        String a = accessToken("a");

        // 1/3 → 33.3, 2/3 → 66.7
        replaceExpenses(1, new long[] {1, 2}, new String[] {"FOOD", "CAFE"});
        expectShares(stats(a, "2026-10"), "CAFE:66.7", "FOOD:33.3");

        // 1/8 → 12.5 (정확), 7/8 → 87.5
        replaceExpenses(1, new long[] {1, 7}, new String[] {"FOOD", "CAFE"});
        expectShares(stats(a, "2026-10"), "CAFE:87.5", "FOOD:12.5");

        // 1/16 = 6.25 → 6.3, 15/16 = 93.75 → 93.8 (HALF_EVEN이면 6.2·93.8). 합계 100.1이어도 보정하지 않는다.
        replaceExpenses(1, new long[] {1, 15}, new String[] {"FOOD", "CAFE"});
        expectShares(stats(a, "2026-10"), "CAFE:93.8", "FOOD:6.3");

        // 1/3 세 개 → 33.3 × 3 = 99.9, 보정하지 않는다.
        replaceExpenses(1, new long[] {1, 1, 1}, new String[] {"TRANSPORT", "FOOD", "CAFE"});
        expectShares(stats(a, "2026-10"), "FOOD:33.3", "CAFE:33.3", "TRANSPORT:33.3");

        // 1/2000 = 0.05 → 0.1, 1/3000 = 0.033… → 0.0
        replaceExpenses(1, new long[] {1999, 1}, new String[] {"FOOD", "CAFE"});
        expectShares(stats(a, "2026-10"), "FOOD:100.0", "CAFE:0.1");
        replaceExpenses(1, new long[] {2999, 1}, new String[] {"FOOD", "CAFE"});
        expectShares(stats(a, "2026-10"), "FOOD:100.0", "CAFE:0.0");
    }

    @Test
    void largeTotalsDoNotOverflow() throws Exception {
        String a = accessToken("a");
        setBudget(1, "2026-10-01", 100000000);
        for (int i = 0; i < 25; i++) {
            seedExpense(1, "2026-10-01", 100000000, "SHOPPING");
        }
        seedExpense(1, "2026-10-13", 33333333, "FOOD");

        // 2,500,000,000 / 2,533,333,333 = 98.68…%, 33,333,333 / 2,533,333,333 = 1.315…%
        stats(a, "2026-10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spentAmount").value(2533333333L))
                .andExpect(jsonPath("$.expenseCount").value(26))
                .andExpect(jsonPath("$.budgetBalanceAmount").value(-2433333333L))
                .andExpect(content().string(Matchers.containsString("\"usagePercent\":2533.3,")));
        expectShares(stats(a, "2026-10"), "SHOPPING:98.7", "FOOD:1.3");
    }

    @Test
    void currentMonthMatchesHome() throws Exception {
        String a = accessToken("a");
        setPet(1, "CAT");
        setBudget(1, "2026-10-01", 10000);
        seedExpense(1, "2026-10-01", 4000, "FOOD");
        seedExpense(1, "2026-10-12", 999, "CAFE");

        String home = mvc.perform(get("/api/v1/home").header("Authorization", "Bearer " + a))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String stats = stats(a, "2026-10").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (String field : List.of("budgetAmount", "spentAmount", "usagePercent", "budgetBalanceAmount")) {
            assertThat(JsonPath.<Object>read(stats, "$." + field)).isEqualTo(JsonPath.read(home, "$." + field));
        }
        // 49.99% → 표시 49.9
        assertThat(stats).contains("\"usagePercent\":49.9,");
    }

    @Test
    void monthUsesSeoulFirstDayToNextMonthFirstDay() throws Exception {
        String a = accessToken("a");
        seedExpense(1, "2026-08-31", 1, "FOOD");
        seedExpense(1, "2026-09-01", 10, "FOOD");
        seedExpense(1, "2026-09-30", 100, "CAFE");
        seedExpense(1, "2026-10-01", 1000, "FOOD");
        seedExpense(1, "2026-10-31", 10000, "CAFE");
        seedExpense(1, "2026-11-01", 100000, "FOOD");

        expectTotals(stats(a, "2026-09"), 110, 2);
        expectTotals(stats(a, "2026-10"), 11000, 2);
    }

    @Test
    void currentMonthFollowsSeoulAtMonthChange() throws Exception {
        accessToken("a");
        seedExpense(1, "2026-10-31", 9000, "FOOD");
        seedExpense(1, "2026-11-01", 500, "CAFE");

        // 2026-10-31T14:59Z = 서울 2026-10-31 23:59 → 11월은 미래
        clock.set(Instant.parse("2026-10-31T14:59:00Z"));
        String a = accessToken("a");
        expectInvalidMonth(stats(a, "2026-11"));
        expectTotals(stats(a, "2026-10"), 9000, 1);

        // 2026-10-31T15:30Z = 서울 2026-11-01 00:30 (UTC는 아직 10월)
        clock.set(Instant.parse("2026-10-31T15:30:00Z"));
        a = accessToken("a");
        expectTotals(stats(a, "2026-11"), 500, 1);
        expectTotals(stats(a, "2026-10"), 9000, 1);
    }

    @Test
    void movingExpenseBetweenMonthsUpdatesBothMonths() throws Exception {
        String a = accessToken("a");
        setPet(1, "CAT");
        setBudget(1, "2026-09-01", 50000);
        mvc.perform(put("/api/v1/months/2026-10/budget").header("Authorization", "Bearer " + a)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100000}")).andExpect(status().isOk());
        String first = createExpense(a, 30000, "2026-10-10", "FOOD");
        String second = createExpense(a, 20000, "2026-10-11", "CAFE");

        expectTotals(stats(a, "2026-10"), 50000, 2);
        expectTotals(stats(a, "2026-09"), 0, 0);

        patchExpense(a, second, "{\"expenseDate\":\"2026-09-30\"}");
        stats(a, "2026-10")
                .andExpect(jsonPath("$.spentAmount").value(30000))
                .andExpect(jsonPath("$.expenseCount").value(1))
                .andExpect(jsonPath("$.budgetBalanceAmount").value(70000))
                .andExpect(jsonPath("$.categories[*].category").value(Matchers.contains("FOOD")));
        stats(a, "2026-09")
                .andExpect(jsonPath("$.spentAmount").value(20000))
                .andExpect(jsonPath("$.expenseCount").value(1))
                .andExpect(jsonPath("$.budgetBalanceAmount").value(30000))
                .andExpect(jsonPath("$.categories[*].category").value(Matchers.contains("CAFE")));
        mvc.perform(get("/api/v1/home").header("Authorization", "Bearer " + a))
                .andExpect(jsonPath("$.spentAmount").value(30000));

        patchExpense(a, first, "{\"amount\":45000,\"category\":\"HEALTH\"}");
        expectShares(stats(a, "2026-10"), "HEALTH:100.0");

        mvc.perform(delete("/api/v1/expenses/" + second).header("Authorization", "Bearer " + a))
                .andExpect(status().isNoContent());
        stats(a, "2026-09")
                .andExpect(jsonPath("$.spentAmount").value(0))
                .andExpect(jsonPath("$.expenseCount").value(0))
                .andExpect(jsonPath("$.categories").isEmpty());
    }

    @Test
    void otherUsersExpensesAndBudgetsAreExcluded() throws Exception {
        String a = accessToken("a");
        String b = accessToken("b");
        setBudget(2, "2026-10-01", 40000);
        setBudget(2, "2026-09-01", 40000);
        seedExpense(1, "2026-10-05", 10000, "FOOD");
        seedExpense(2, "2026-10-05", 30000, "FOOD");
        seedExpense(2, "2026-10-06", 5000, "CAFE");
        seedExpense(2, "2026-09-06", 5000, "CAFE");

        stats(a, "2026-10")
                .andExpect(content().json("""
                        {"month":"2026-10","budgetAmount":null,"spentAmount":10000,"expenseCount":1,
                         "usagePercent":null,"budgetBalanceAmount":null,
                         "categories":[{"category":"FOOD","spentAmount":10000,"expenseCount":1,"sharePercent":100.0}]}
                        """, JsonCompareMode.STRICT));
        stats(a, "2026-09")
                .andExpect(jsonPath("$.spentAmount").value(0))
                .andExpect(jsonPath("$.budgetAmount").value(Matchers.nullValue()));
        stats(b, "2026-10")
                .andExpect(jsonPath("$.spentAmount").value(35000))
                .andExpect(jsonPath("$.expenseCount").value(2))
                .andExpect(jsonPath("$.budgetAmount").value(40000));
    }

    @Test
    void invalidMonthIsBadRequest() throws Exception {
        String a = accessToken("a");
        for (String month : List.of("2026-11", "2027-01", "1999-12", "0000-01", "2026-13", "2026-00", "2026-1",
                "202610", "2026-10-01", "+2026-10", "abcd-ef")) {
            expectInvalidMonth(stats(a, month));
        }
    }

    @Test
    void statsRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/months/2026-10/stats"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
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

    private void replaceExpenses(long userId, long[] amounts, String[] categories) {
        jdbc.update("DELETE FROM expenses WHERE user_id = ?", userId);
        for (int i = 0; i < amounts.length; i++) {
            seedExpense(userId, "2026-10-13", amounts[i], categories[i]);
        }
    }

    private String accessToken(String id) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"ok:" + id + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private ResultActions stats(String token, String month) throws Exception {
        return mvc.perform(get("/api/v1/months/" + month + "/stats").header("Authorization", "Bearer " + token));
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

    private static void expectTotals(ResultActions result, long spent, int count) throws Exception {
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.spentAmount").value(spent))
                .andExpect(jsonPath("$.expenseCount").value(count));
    }

    // "CATEGORY:share" 순서대로 비교한다. sharePercent는 소수 한 자리 JSON 숫자 문자열 그대로 확인한다.
    private static void expectShares(ResultActions result, String... expected) throws Exception {
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.categories", Matchers.hasSize(expected.length)));
        for (int i = 0; i < expected.length; i++) {
            String[] parts = expected[i].split(":");
            result.andExpect(jsonPath("$.categories[" + i + "].category").value(parts[0]));
        }
        String body = result.andReturn().getResponse().getContentAsString();
        for (String e : expected) {
            String[] parts = e.split(":");
            assertThat(body).contains("{\"category\":\"" + parts[0] + "\",");
            assertThat(body).containsPattern(
                    "\\{\"category\":\"" + parts[0] + "\",[^}]*\"sharePercent\":" + parts[1].replace(".", "\\.") + "}");
        }
    }

    private static void expectInvalidMonth(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fields[0].field").value("month"));
    }
}
