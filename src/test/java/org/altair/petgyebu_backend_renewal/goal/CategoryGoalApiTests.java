package org.altair.petgyebu_backend_renewal.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.altair.petgyebu_backend_renewal.budget.MonthlyBudgetRepository;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.altair.petgyebu_backend_renewal.support.MutableClock;
import org.altair.petgyebu_backend_renewal.support.TestAuthConfig;
import org.altair.petgyebu_backend_renewal.user.UserService;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.node.JsonNodeFactory;

// TestAuthConfig.START = 2026-10-13T00:00Z = 서울 2026-10-13 09:00 → 현재 월 2026-10
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class CategoryGoalApiTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired CategoryGoalService goalService;
    @Autowired CategoryGoalRepository goals;
    @Autowired MonthlyBudgetRepository budgets;
    @Autowired UserService userService;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE expenses, category_goals, monthly_budgets, users RESTART IDENTITY");
        clock.set(TestAuthConfig.START);
    }

    @Test
    void getReturnsExactShape() throws Exception {
        String a = accessToken("a");
        setBudget(1, "2026-10-01", 500000);
        seedGoal(1, "2026-10-01", "CAFE", 50000);
        seedGoal(1, "2026-10-01", "FOOD", 100000);
        jdbc.update("INSERT INTO expenses (user_id, amount, expense_date, category) VALUES (1, 32000, DATE '2026-10-05', 'CAFE')");

        getGoals(a, "2026-10").andExpect(status().isOk()).andExpect(content().json("""
                {"month":"2026-10","budgetAmount":500000,"goalTotalAmount":150000,"goalsExceedBudget":false,
                 "goals":[{"category":"FOOD","goalAmount":100000,"spentAmount":0,"remainingAmount":100000,"usagePercent":0.0},
                          {"category":"CAFE","goalAmount":50000,"spentAmount":32000,"remainingAmount":18000,"usagePercent":64.0}]}
                """, JsonCompareMode.STRICT));
    }

    @Test
    void getWithoutBudgetOrGoals() throws Exception {
        String a = accessToken("a");

        getGoals(a, "2026-10").andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-10"))
                .andExpect(jsonPath("$.budgetAmount").hasJsonPath())
                .andExpect(jsonPath("$.budgetAmount").isEmpty())
                .andExpect(jsonPath("$.goalTotalAmount").value(0))
                .andExpect(jsonPath("$.goalsExceedBudget").value(false))
                .andExpect(jsonPath("$.goals").isEmpty());
    }

    @Test
    void putCreatesThenReplacesInOneRow() throws Exception {
        String a = accessToken("a");
        setBudget(1, "2026-10-01", 100000);

        putGoal(a, "2026-10", "CAFE", "{\"amount\":30000}").andExpect(status().isOk())
                .andExpect(content().json("{\"month\":\"2026-10\",\"category\":\"CAFE\",\"amount\":30000}",
                        JsonCompareMode.STRICT));
        Map<String, Object> created = goalRow();
        assertThat(created.get("category")).isEqualTo("CAFE");
        assertThat(created.get("goal_month")).isEqualTo(java.sql.Date.valueOf("2026-10-01"));
        assertThat(timestamp(created, "created_at")).isEqualTo(TestAuthConfig.START);
        assertThat(timestamp(created, "updated_at")).isEqualTo(TestAuthConfig.START);

        clock.advance(Duration.ofMinutes(5));
        putGoal(a, "2026-10", "CAFE", "{\"amount\":40000}").andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(40000));

        assertThat(goalCount()).isEqualTo(1);
        Map<String, Object> updated = goalRow();
        assertThat(updated.get("id")).isEqualTo(created.get("id"));
        assertThat(updated.get("amount")).isEqualTo(40000L);
        assertThat(timestamp(updated, "created_at")).isEqualTo(TestAuthConfig.START);
        assertThat(timestamp(updated, "updated_at")).isEqualTo(TestAuthConfig.START.plus(Duration.ofMinutes(5)));
    }

    @Test
    void goalsMayEqualBudgetButNotExceedIt() throws Exception {
        String a = accessToken("a");
        setBudget(1, "2026-10-01", 100000);
        seedGoal(1, "2026-10-01", "FOOD", 60000);

        putGoal(a, "2026-10", "CAFE", "{\"amount\":40000}").andExpect(status().isOk());
        putGoal(a, "2026-10", "HEALTH", "{\"amount\":1}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_GOALS_EXCEED_BUDGET"));
        assertThat(goalCount()).isEqualTo(2);
    }

    @Test
    void replacingSameCategoryCountsOnlyNewAmount() throws Exception {
        String a = accessToken("a");
        setBudget(1, "2026-10-01", 100);
        seedGoal(1, "2026-10-01", "CAFE", 60);

        putGoal(a, "2026-10", "CAFE", "{\"amount\":100}").andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(100));
    }

    @Test
    void putWithoutMonthBudgetIsBudgetRequired() throws Exception {
        String a = accessToken("a");

        putGoal(a, "2026-10", "CAFE", "{\"amount\":1000}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUDGET_REQUIRED"));
        assertThat(goalCount()).isZero();

        setBudget(1, "2026-09-01", 100000);
        putGoal(a, "2026-10", "CAFE", "{\"amount\":1000}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUDGET_REQUIRED"));
        assertThat(goalCount()).isZero();
    }

    @Test
    void putIsRejectedWhileGoalsStillExceedLoweredBudget() throws Exception {
        String a = accessToken("a");
        setBudget(1, "2026-10-01", 100000);
        seedGoal(1, "2026-10-01", "FOOD", 60000);
        seedGoal(1, "2026-10-01", "CAFE", 40000);

        mvc.perform(put("/api/v1/months/2026-10/budget").header("Authorization", "Bearer " + a)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":50000}")).andExpect(status().isOk());
        getGoals(a, "2026-10").andExpect(status().isOk())
                .andExpect(jsonPath("$.goalsExceedBudget").value(true))
                .andExpect(jsonPath("$.goalTotalAmount").value(100000));

        putGoal(a, "2026-10", "CAFE", "{\"amount\":30000}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_GOALS_EXCEED_BUDGET"));
        assertThat(jdbc.queryForObject("SELECT amount FROM category_goals WHERE category = 'CAFE'", Long.class))
                .isEqualTo(40000L);

        deleteGoal(a, "2026-10", "FOOD").andExpect(status().isNoContent());
        putGoal(a, "2026-10", "CAFE", "{\"amount\":30000}").andExpect(status().isOk());
    }

    @Test
    void deleteIsIdempotent() throws Exception {
        String a = accessToken("a");
        seedGoal(1, "2026-10-01", "CAFE", 1000);
        seedGoal(1, "2026-10-01", "FOOD", 2000);

        deleteGoal(a, "2026-10", "CAFE").andExpect(status().isNoContent());
        deleteGoal(a, "2026-10", "CAFE").andExpect(status().isNoContent());
        deleteGoal(a, "2026-09", "CAFE").andExpect(status().isNoContent());

        assertThat(jdbc.queryForList("SELECT category FROM category_goals", String.class)).containsExactly("FOOD");
    }

    @Test
    void pastMonthAllowedFutureRejected() throws Exception {
        String a = accessToken("a");
        setBudget(1, "2026-09-01", 200000);

        putGoal(a, "2026-09", "CAFE", "{\"amount\":1000}").andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-09"));
        expectInvalid(putGoal(a, "2026-11", "CAFE", "{\"amount\":1000}"), "month");
        expectInvalid(getGoals(a, "2026-11"), "month");
        expectInvalid(deleteGoal(a, "2026-11", "CAFE"), "month");
    }

    @Test
    void validationOrderIsMonthCategoryBodyThenBudget() throws Exception {
        String a = accessToken("a");

        expectInvalid(putGoal(a, "2026-11", "cafe", "[]"), "month");
        expectInvalid(putGoal(a, "2026-10", "cafe", "[]"), "category");
        putGoal(a, "2026-10", "CAFE", "[]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        expectInvalid(putGoal(a, "2026-10", "CAFE", "{\"amount\":0}"), "amount");
        putGoal(a, "2026-10", "CAFE", "{\"amount\":1000}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUDGET_REQUIRED"));
        expectInvalid(deleteGoal(a, "2026-10", "cafe"), "category");
        assertThat(goalCount()).isZero();
    }

    @Test
    void goalsAreIsolatedPerUser() throws Exception {
        String a = accessToken("a");
        String b = accessToken("b");
        setBudget(1, "2026-10-01", 100000);
        setBudget(2, "2026-10-01", 1000);
        seedGoal(1, "2026-10-01", "CAFE", 50000);

        getGoals(b, "2026-10").andExpect(status().isOk()).andExpect(jsonPath("$.goals").isEmpty());
        deleteGoal(b, "2026-10", "CAFE").andExpect(status().isNoContent());
        assertThat(goalCount()).isEqualTo(1);

        putGoal(b, "2026-10", "CAFE", "{\"amount\":50000}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_GOALS_EXCEED_BUDGET"));
        putGoal(b, "2026-10", "CAFE", "{\"amount\":1000}").andExpect(status().isOk());
        getGoals(a, "2026-10").andExpect(jsonPath("$.goals[0].goalAmount").value(50000));
    }

    @Test
    void endpointsRequireAuthentication() throws Exception {
        String url = "/api/v1/months/2026-10/category-goals";
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(put(url + "/CAFE").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(url + "/CAFE")).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentPutsNeverExceedBudget() throws Exception {
        accessToken("a");
        setBudget(1, "2026-10-01", 100000);
        List<String> categories = List.of("FOOD", "CAFE", "TRANSPORT", "SHOPPING", "HEALTH", "LIVING", "OTHER");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        int succeeded = 0;
        try {
            List<Future<CategoryGoal>> results = new ArrayList<>();
            for (String category : categories) {
                results.add(pool.submit(() -> {
                    start.await();
                    return goalService.put(1L, "2026-10", category,
                            JsonNodeFactory.instance.objectNode().put("amount", 30000));
                }));
            }
            start.countDown();
            for (Future<CategoryGoal> result : results) {
                try {
                    result.get(30, TimeUnit.SECONDS);
                    succeeded++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOfSatisfying(ApiException.class, api -> {
                        assertThat(api.status().value()).isEqualTo(409);
                        assertThat(api.code()).isEqualTo("CATEGORY_GOALS_EXCEED_BUDGET");
                    });
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM category_goals", Long.class))
                .isLessThanOrEqualTo(100000L);
        assertThat(succeeded).isEqualTo(3);
        assertThat(goalCount()).isEqualTo(3);
    }

    // 목표 PUT은 예산 행을 먼저 잠그고 목표 행을 쓴다. 탈퇴가 목표를 먼저 지우면 서로 기다려 교착 상태가 된다.
    @Test
    void withdrawalDuringGoalPutDoesNotDeadlock() throws Exception {
        accessToken("a");
        setBudget(1, "2026-10-01", 100000);
        seedGoal(1, "2026-10-01", "CAFE", 30000);
        LocalDate month = LocalDate.of(2026, 10, 1);
        CountDownLatch budgetLocked = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> put = pool.submit(() -> tx.executeWithoutResult(status -> {
                budgets.findForUpdate(1, month);
                budgetLocked.countDown();
                awaitBlockedOnLock();
                goals.upsert(1, month, "CAFE", 40000, OffsetDateTime.now());
            }));
            assertThat(budgetLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> withdraw = pool.submit(() -> userService.withdraw(1));

            put.get(30, TimeUnit.SECONDS);
            withdraw.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM expenses WHERE user_id = 1", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM category_goals WHERE user_id = 1", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM monthly_budgets WHERE user_id = 1", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = 1", Integer.class)).isZero();
    }

    // 다른 세션이 잠금을 기다리는 것이 보일 때까지 폴링한다.
    private void awaitBlockedOnLock() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            // 트랜잭션 안에서는 첫 조회 때의 세션 목록이 유지돼 그 뒤에 연결된 탈퇴 세션이 보이지 않는다.
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND datname = current_database()
                    """, Integer.class);
            if (waiting != null && waiting >= 1) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new IllegalStateException("탈퇴 트랜잭션이 잠금 대기에 들어가지 않았다.");
    }

    private void setBudget(long userId, String month, long amount) {
        jdbc.update("INSERT INTO monthly_budgets (user_id, budget_month, amount) VALUES (?, ?::date, ?)",
                userId, month, amount);
    }

    private void seedGoal(long userId, String month, String category, long amount) {
        jdbc.update("INSERT INTO category_goals (user_id, goal_month, category, amount) VALUES (?, ?::date, ?, ?)",
                userId, month, category, amount);
    }

    private int goalCount() {
        return jdbc.queryForObject("SELECT count(*) FROM category_goals", Integer.class);
    }

    private Map<String, Object> goalRow() {
        return jdbc.queryForMap("SELECT * FROM category_goals");
    }

    private Instant timestamp(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value instanceof OffsetDateTime odt ? odt.toInstant() : ((java.sql.Timestamp) value).toInstant();
    }

    private String accessToken(String id) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"ok:" + id + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private ResultActions getGoals(String token, String month) throws Exception {
        return mvc.perform(get("/api/v1/months/" + month + "/category-goals").header("Authorization", "Bearer " + token));
    }

    private ResultActions putGoal(String token, String month, String category, String json) throws Exception {
        return mvc.perform(put("/api/v1/months/" + month + "/category-goals/" + category)
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions deleteGoal(String token, String month, String category) throws Exception {
        return mvc.perform(delete("/api/v1/months/" + month + "/category-goals/" + category)
                .header("Authorization", "Bearer " + token));
    }

    private static void expectInvalid(ResultActions result, String field) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fields[0].field").value(field));
    }
}
