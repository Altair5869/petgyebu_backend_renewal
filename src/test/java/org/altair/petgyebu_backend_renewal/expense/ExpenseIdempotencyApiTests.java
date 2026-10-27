package org.altair.petgyebu_backend_renewal.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

// Idempotency-Key 헤더(선택)가 있는 지출 생성 계약. TestAuthConfig.START = 서울 2026-10-13 09:00
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class ExpenseIdempotencyApiTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final String BODY = "{\"amount\":1000,\"expenseDate\":\"2026-10-13\",\"category\":\"FOOD\",\"memo\":\"m\"}";

    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE expenses, monthly_budgets, users RESTART IDENTITY");
        clock.set(TestAuthConfig.START);
    }

    @Test
    void withoutKeyEachRequestCreatesAnExpense() throws Exception {
        String a = accessToken("a");

        create(a, BODY, null).andExpect(status().isCreated());
        create(a, BODY, null).andExpect(status().isCreated());

        assertThat(expenseCount()).isEqualTo(2);
    }

    @Test
    void sameKeyAndBodyReturns201ThenExisting200() throws Exception {
        String a = accessToken("a");
        String key = UUID.randomUUID().toString();

        String first = create(a, BODY, key).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = create(a, BODY, key).andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(1000))
                .andExpect(jsonPath("$.category").value("FOOD"))
                .andExpect(jsonPath("$.memo").value("m"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(expenseCount()).isEqualTo(1);
    }

    @Test
    void blankMemoReplayEqualsOmittedMemo() throws Exception {
        String a = accessToken("a");
        String key = UUID.randomUUID().toString();

        create(a, "{\"amount\":1000,\"expenseDate\":\"2026-10-13\"}", key).andExpect(status().isCreated());
        create(a, "{\"amount\":1000,\"expenseDate\":\"2026-10-13\",\"memo\":\"   \"}", key)
                .andExpect(status().isOk());

        assertThat(expenseCount()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyReturns409() throws Exception {
        String a = accessToken("a");
        String key = UUID.randomUUID().toString();
        create(a, BODY, key).andExpect(status().isCreated());

        for (String other : List.of(
                "{\"amount\":2000,\"expenseDate\":\"2026-10-13\",\"category\":\"FOOD\",\"memo\":\"m\"}",
                "{\"amount\":1000,\"expenseDate\":\"2026-10-12\",\"category\":\"FOOD\",\"memo\":\"m\"}",
                "{\"amount\":1000,\"expenseDate\":\"2026-10-13\",\"category\":\"CAFE\",\"memo\":\"m\"}",
                "{\"amount\":1000,\"expenseDate\":\"2026-10-13\",\"category\":\"FOOD\",\"memo\":\"x\"}")) {
            create(a, other, key).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                    .andExpect(jsonPath("$.message").isString());
        }
        assertThat(expenseCount()).isEqualTo(1);
    }

    @Test
    void invalidKeyFormatReturns400() throws Exception {
        String a = accessToken("a");

        for (String key : List.of("not-a-uuid", "12345", "123e4567e89b12d3a456426614174000", " ", "123e4567-e89b-12d3-a456-42661417400g")) {
            create(a, BODY, key).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.fields[0].field").value("Idempotency-Key"));
        }
        assertThat(expenseCount()).isZero();
    }

    @Test
    void uppercaseKeyIsTheSameKeyAsLowercase() throws Exception {
        String a = accessToken("a");
        String key = UUID.randomUUID().toString();

        create(a, BODY, key.toLowerCase()).andExpect(status().isCreated());
        create(a, BODY, key.toUpperCase()).andExpect(status().isOk());

        assertThat(expenseCount()).isEqualTo(1);
    }

    @Test
    void bodyValidationRunsBeforeKeyLookup() throws Exception {
        String a = accessToken("a");
        String key = UUID.randomUUID().toString();
        create(a, BODY, key).andExpect(status().isCreated());

        create(a, "{\"amount\":0,\"expenseDate\":\"2026-10-13\"}", key).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("amount"));
    }

    @Test
    void sameKeyForDifferentUsersCreatesOneEachAndDoesNotConflict() throws Exception {
        String a = accessToken("a");
        String b = accessToken("b");
        String key = UUID.randomUUID().toString();

        create(a, BODY, key).andExpect(status().isCreated());
        create(b, "{\"amount\":5,\"expenseDate\":\"2026-10-13\"}", key).andExpect(status().isCreated());

        assertThat(expenseCount()).isEqualTo(2);
    }

    @Test
    void sameKeyAfterDeleteCreatesANewExpense() throws Exception {
        String a = accessToken("a");
        String key = UUID.randomUUID().toString();
        long id = ((Number) JsonPath.read(create(a, BODY, key).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString(), "$.id")).longValue();
        mvc.perform(delete("/api/v1/expenses/" + id).header("Authorization", "Bearer " + a))
                .andExpect(status().isNoContent());

        create(a, BODY, key).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id + 1));

        assertThat(expenseCount()).isEqualTo(1);
    }

    @Test
    void omittedCategoryEqualsExplicitOtherAndOmittedMemoEqualsNull() throws Exception {
        String a = accessToken("a");
        String key = UUID.randomUUID().toString();

        create(a, "{\"amount\":1,\"expenseDate\":\"2026-10-13\"}", key).andExpect(status().isCreated());
        create(a, "{\"amount\":1,\"expenseDate\":\"2026-10-13\",\"category\":\"OTHER\",\"memo\":null}", key)
                .andExpect(status().isOk());
        create(a, "{\"amount\":1,\"expenseDate\":\"2026-10-13\",\"category\":null,\"memo\":\"  \"}", key)
                .andExpect(status().isOk());

        assertThat(expenseCount()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateExactlyOneExpense() throws Exception {
        String a = accessToken("a");
        int n = 8;
        for (int round = 0; round < 3; round++) {
            String key = UUID.randomUUID().toString();
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(n);
            try {
                List<Future<Integer>> results = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    Callable<Integer> call = () -> {
                        ready.countDown();
                        go.await();
                        return create(a, BODY, key).andReturn().getResponse().getStatus();
                    };
                    results.add(pool.submit(call));
                }
                ready.await();
                go.countDown();
                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> f : results) {
                    statuses.add(f.get());
                }
                assertThat(statuses).containsOnly(200, 201);
                assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM expenses WHERE idempotency_key = ?::uuid",
                    Integer.class, key)).isEqualTo(1);
        }
    }

    private int expenseCount() {
        return jdbc.queryForObject("SELECT count(*) FROM expenses", Integer.class);
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
}
