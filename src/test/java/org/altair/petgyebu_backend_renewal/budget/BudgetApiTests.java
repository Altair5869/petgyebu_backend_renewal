package org.altair.petgyebu_backend_renewal.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.altair.petgyebu_backend_renewal.common.ApiException;
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
import tools.jackson.databind.node.JsonNodeFactory;

// TestAuthConfig.START = 2026-10-13T00:00Z = 서울 2026-10-13 09:00 → 현재 월 2026-10
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class BudgetApiTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired BudgetService budgetService;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE expenses, monthly_budgets, users RESTART IDENTITY");
        clock.set(TestAuthConfig.START);
    }

    // --- 조회 ---

    @Test
    void getUnsetCurrentAndPastMonthReturnsNullAmount() throws Exception {
        String a = accessToken("a");

        for (String month : List.of("2026-10", "2026-09", "2000-01")) {
            getBudget(a, month)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.month").value(month))
                    .andExpect(jsonPath("$.amount").hasJsonPath())
                    .andExpect(jsonPath("$.amount").isEmpty());
        }
        assertThat(budgetCount()).isZero();
    }

    @Test
    void getReturnsStoredPastBudget() throws Exception {
        String a = accessToken("a");
        jdbc.update("INSERT INTO monthly_budgets (user_id, budget_month, amount) VALUES (1, DATE '2026-09-01', 300000)");

        getBudget(a, "2026-09")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-09"))
                .andExpect(jsonPath("$.amount").value(300000));
    }

    @Test
    void getRejectsMalformedFutureAndPre2000Month() throws Exception {
        String a = accessToken("a");

        for (String month : List.of("2026-11", "2027-01", "1999-12", "0000-01", "2026-13", "2026-1", "202610",
                "abcd-ef", "2026-10-01")) {
            expectInvalid(getBudget(a, month), "month");
        }
    }

    // --- 설정·변경 ---

    @Test
    void putCreatesThenReplacesCurrentMonthBudgetInOneRow() throws Exception {
        String a = accessToken("a");

        putBudget(a, "2026-10", "{\"amount\":500000}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-10"))
                .andExpect(jsonPath("$.amount").value(500000));
        Map<String, Object> created = budgetRow();
        assertThat(created.get("user_id")).isEqualTo(1L);
        assertThat(created.get("budget_month")).isEqualTo(java.sql.Date.valueOf(LocalDate.of(2026, 10, 1)));
        assertThat(created.get("amount")).isEqualTo(500000L);
        assertThat(timestamp(created, "created_at")).isEqualTo(TestAuthConfig.START);
        assertThat(timestamp(created, "updated_at")).isEqualTo(TestAuthConfig.START);

        clock.advance(Duration.ofMinutes(5));
        putBudget(a, "2026-10", "{\"amount\":700000}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-10"))
                .andExpect(jsonPath("$.amount").value(700000));

        assertThat(budgetCount()).isEqualTo(1);
        Map<String, Object> updated = budgetRow();
        assertThat(updated.get("id")).isEqualTo(created.get("id"));
        assertThat(updated.get("amount")).isEqualTo(700000L);
        assertThat(timestamp(updated, "created_at")).isEqualTo(TestAuthConfig.START);
        assertThat(timestamp(updated, "updated_at")).isEqualTo(TestAuthConfig.START.plus(Duration.ofMinutes(5)));

        getBudget(a, "2026-10").andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(700000));
    }

    @Test
    void putRejectsPastAndFutureMonth() throws Exception {
        String a = accessToken("a");

        for (String month : List.of("2026-09", "2000-01", "2026-11", "2027-10")) {
            expectInvalid(putBudget(a, month, "{\"amount\":1000}"), "month");
        }
        assertThat(budgetCount()).isZero();
    }

    @Test
    void putRejectsMalformedMonth() throws Exception {
        String a = accessToken("a");

        for (String month : List.of("1999-12", "2026-13", "2026-1", "202610", "2026-10-01")) {
            expectInvalid(putBudget(a, month, "{\"amount\":1000}"), "month");
        }
        assertThat(budgetCount()).isZero();
    }

    @Test
    void currentMonthFollowsSeoulAtMonthBoundary() throws Exception {
        // 2026-10-31T14:59Z = 서울 2026-10-31 23:59 → 11월은 아직 미래
        clock.set(Instant.parse("2026-10-31T14:59:00Z"));
        String a = accessToken("a");
        expectInvalid(putBudget(a, "2026-11", "{\"amount\":1000}"), "month");
        expectInvalid(getBudget(a, "2026-11"), "month");
        putBudget(a, "2026-10", "{\"amount\":1000}").andExpect(status().isOk());

        // 2026-10-31T15:30Z = 서울 2026-11-01 00:30 (UTC는 아직 10월)
        clock.set(Instant.parse("2026-10-31T15:30:00Z"));
        a = accessToken("a");
        putBudget(a, "2026-11", "{\"amount\":2000}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-11"))
                .andExpect(jsonPath("$.amount").value(2000));
        expectInvalid(putBudget(a, "2026-10", "{\"amount\":3000}"), "month");

        assertThat(jdbc.queryForList("SELECT budget_month::text || ':' || amount FROM monthly_budgets ORDER BY id",
                String.class)).containsExactly("2026-10-01:1000", "2026-11-01:2000");
        getBudget(a, "2026-10").andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(1000));
    }

    @Test
    void amountBoundsAreOneToOneHundredMillion() throws Exception {
        String a = accessToken("a");

        putBudget(a, "2026-10", "{\"amount\":1}").andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(1));
        putBudget(a, "2026-10", "{\"amount\":100000000}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(100000000));
        assertThat(budgetCount()).isEqualTo(1);
    }

    @Test
    void putRejectsInvalidAmountWithoutChangingBudget() throws Exception {
        String a = accessToken("a");
        putBudget(a, "2026-10", "{\"amount\":5000}").andExpect(status().isOk());

        for (String amount : List.of("0", "-1", "100000001", "1.5", "\"1000\"", "null", "true",
                "99999999999999999999")) {
            expectInvalid(putBudget(a, "2026-10", "{\"amount\":" + amount + "}"), "amount");
        }
        expectInvalid(putBudget(a, "2026-10", "{}"), "amount");

        assertThat(budgetCount()).isEqualTo(1);
        assertThat(budgetRow().get("amount")).isEqualTo(5000L);
    }

    @Test
    void putRejectsNonObjectOrUnreadableBody() throws Exception {
        String a = accessToken("a");

        for (String body : List.of("[]", "1000", "null", "{")) {
            putBudget(a, "2026-10", body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertThat(budgetCount()).isZero();
    }

    // --- 사용자 격리 ---

    @Test
    void budgetsAreIsolatedPerUser() throws Exception {
        String a = accessToken("a");
        String b = accessToken("b");
        putBudget(a, "2026-10", "{\"amount\":100000}").andExpect(status().isOk());
        jdbc.update("INSERT INTO monthly_budgets (user_id, budget_month, amount) VALUES (1, DATE '2026-09-01', 90000)");

        getBudget(b, "2026-10").andExpect(status().isOk()).andExpect(jsonPath("$.amount").isEmpty());
        getBudget(b, "2026-09").andExpect(status().isOk()).andExpect(jsonPath("$.amount").isEmpty());

        putBudget(b, "2026-10", "{\"amount\":200000}").andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(200000));

        getBudget(a, "2026-10").andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(100000));
        assertThat(jdbc.queryForList(
                "SELECT user_id || ':' || budget_month::text || ':' || amount FROM monthly_budgets ORDER BY user_id, budget_month",
                String.class)).containsExactly("1:2026-09-01:90000", "1:2026-10-01:100000", "2:2026-10-01:200000");
    }

    // --- 동시성 ---

    @Test
    void concurrentPutsForSameUserAndMonthAllSucceedWithOneRow() throws Exception {
        accessToken("a");
        int threads = 10;
        List<Long> amounts = new ArrayList<>();
        for (int i = 1; i <= threads; i++) {
            amounts.add(i * 1000L);
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<MonthlyBudget>> results = new ArrayList<>();
            for (long amount : amounts) {
                results.add(pool.submit(() -> {
                    start.await();
                    return budgetService.put(1L, "2026-10", JsonNodeFactory.instance.objectNode().put("amount", amount));
                }));
            }
            start.countDown();
            for (Future<MonthlyBudget> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS).amount()).isIn(amounts);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(budgetCount()).isEqualTo(1);
        assertThat(budgetRow().get("amount")).isIn(amounts);
    }

    // --- 인증 ---

    @Test
    void budgetEndpointsRequireAuthentication() throws Exception {
        expectUnauthorized(mvc.perform(get("/api/v1/months/2026-10/budget")));
        expectUnauthorized(mvc.perform(put("/api/v1/months/2026-10/budget")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000}")));
        assertThat(budgetCount()).isZero();
    }

    @Test
    void putAfterUserRowDeletedIsUnauthorized() throws Exception {
        accessToken("a");
        // 인증 통과 직후 동시 탈퇴로 사용자 행이 사라진 상황. HTTP는 인증에서 먼저 거절되므로 서비스를 직접 호출한다.
        jdbc.update("DELETE FROM users WHERE id = 1");

        assertThatThrownBy(() -> budgetService.put(1, "2026-10", JsonNodeFactory.instance.objectNode().put("amount", 1000)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(401);
                    assertThat(e.code()).isEqualTo("UNAUTHORIZED");
                });
        assertThat(budgetCount()).isZero();
    }

    // --- 도우미 ---

    private int budgetCount() {
        return jdbc.queryForObject("SELECT count(*) FROM monthly_budgets", Integer.class);
    }

    private Map<String, Object> budgetRow() {
        return jdbc.queryForMap("SELECT * FROM monthly_budgets");
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

    private ResultActions getBudget(String token, String month) throws Exception {
        return mvc.perform(get("/api/v1/months/" + month + "/budget").header("Authorization", "Bearer " + token));
    }

    private ResultActions putBudget(String token, String month, String json) throws Exception {
        return mvc.perform(put("/api/v1/months/" + month + "/budget").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static void expectInvalid(ResultActions result, String field) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fields[0].field").value(field));
    }

    private static void expectUnauthorized(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
