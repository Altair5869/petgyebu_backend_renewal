package org.altair.petgyebu_backend_renewal.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// TestAuthConfig.START = 2026-10-13T00:00Z = 서울 2026-10-13 09:00
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class ExpenseApiTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExpenseService expenseService;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE expenses, category_goals, monthly_budgets, users RESTART IDENTITY");
        clock.set(TestAuthConfig.START);
    }

    @Test
    void createReturns201WithItemShape() throws Exception {
        String a = accessToken("a");

        create(a, "{\"amount\":12000,\"expenseDate\":\"2026-10-13\",\"category\":\"FOOD\",\"memo\":\"사료\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.amount").value(12000))
                .andExpect(jsonPath("$.expenseDate").value("2026-10-13"))
                .andExpect(jsonPath("$.category").value("FOOD"))
                .andExpect(jsonPath("$.memo").value("사료"))
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").doesNotExist());

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM expenses WHERE id = 1");
        assertThat(row.get("user_id")).isEqualTo(1L);
        assertThat(row.get("amount")).isEqualTo(12000L);
        assertThat(row.get("category")).isEqualTo("FOOD");
    }

    @Test
    void createDefaultsCategoryToOtherWhenOmittedOrNull() throws Exception {
        String a = accessToken("a");

        create(a, "{\"amount\":1,\"expenseDate\":\"2026-10-13\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(1))
                .andExpect(jsonPath("$.category").value("OTHER"));
        create(a, "{\"amount\":1,\"expenseDate\":\"2026-10-13\",\"category\":null}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("OTHER"));
    }

    @Test
    void createStoresBlankOrMissingMemoAsNull() throws Exception {
        String a = accessToken("a");

        for (String memo : List.of("", ",\"memo\":null", ",\"memo\":\"\"", ",\"memo\":\"   \"")) {
            expectNullMemo(create(a, "{\"amount\":1,\"expenseDate\":\"2026-10-13\"" + memo + "}")
                    .andExpect(status().isCreated()));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM expenses WHERE memo IS NULL", Integer.class)).isEqualTo(4);
    }

    @Test
    void memoLimitIs100CodePoints() throws Exception {
        String a = accessToken("a");
        // 이모지는 UTF-16 두 글자지만 코드 포인트 하나다.
        String hundred = "😀".repeat(100);

        create(a, body(1, "2026-10-13", "FOOD", hundred))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memo").value(hundred));
        expectInvalid(create(a, body(1, "2026-10-13", "FOOD", hundred + "a")), "memo");
        expectInvalid(create(a, body(1, "2026-10-13", "FOOD", "a".repeat(101))), "memo");
    }

    @Test
    void createRejectsInvalidAmount() throws Exception {
        String a = accessToken("a");

        for (String amount : List.of("0", "-1", "1.5", "\"1000\"", "null")) {
            expectInvalid(create(a, "{\"amount\":" + amount + ",\"expenseDate\":\"2026-10-13\"}"), "amount");
        }
        expectInvalid(create(a, "{\"expenseDate\":\"2026-10-13\"}"), "amount");
        assertThat(expenseCount()).isZero();
    }

    @Test
    void createRejectsInvalidDate() throws Exception {
        String a = accessToken("a");

        for (String date : List.of("null", "\"2026/10/01\"", "\"2026-13-01\"", "\"2026-02-30\"", "\"2026-10\"", "20261001")) {
            expectInvalid(create(a, "{\"amount\":1,\"expenseDate\":" + date + "}"), "expenseDate");
        }
        expectInvalid(create(a, "{\"amount\":1}"), "expenseDate");
        assertThat(expenseCount()).isZero();
    }

    @Test
    void createRejectsUnknownCategoryAndNonTextMemo() throws Exception {
        String a = accessToken("a");

        expectInvalid(create(a, body(1, "2026-10-13", "COFFEE", null)), "category");
        expectInvalid(create(a, body(1, "2026-10-13", "food", null)), "category");
        expectInvalid(create(a, "{\"amount\":1,\"expenseDate\":\"2026-10-13\",\"memo\":5}"), "memo");
        create(a, "not json").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(expenseCount()).isZero();
    }

    @Test
    void createAllowsDuplicates() throws Exception {
        String a = accessToken("a");

        create(a, body(5000, "2026-10-13", "CAFE", null)).andExpect(status().isCreated());
        create(a, body(5000, "2026-10-13", "CAFE", null)).andExpect(status().isCreated());

        assertThat(expenseCount()).isEqualTo(2);
    }

    @Test
    void todayIsSeoulDateEvenWhileUtcIsStillYesterday() throws Exception {
        // 2026-10-13T15:30Z = 서울 2026-10-14 00:30
        clock.set(Instant.parse("2026-10-13T15:30:00Z"));
        String a = accessToken("a");

        create(a, body(1, "2026-10-14", "FOOD", null)).andExpect(status().isCreated());
        expectInvalid(create(a, body(1, "2026-10-15", "FOOD", null)), "expenseDate");
    }

    @Test
    void seoulTomorrowIsFutureJustBeforeSeoulMidnight() throws Exception {
        // 2026-10-13T14:59Z = 서울 2026-10-13 23:59
        clock.set(Instant.parse("2026-10-13T14:59:00Z"));
        String a = accessToken("a");

        create(a, body(1, "2026-10-13", "FOOD", null)).andExpect(status().isCreated());
        expectInvalid(create(a, body(1, "2026-10-14", "FOOD", null)), "expenseDate");
        create(a, body(1, "2020-01-01", "FOOD", null)).andExpect(status().isCreated());
    }

    @Test
    void getReturnsOwnExpense() throws Exception {
        String a = accessToken("a");
        create(a, body(3000, "2026-10-12", "HEALTH", null));

        getOne(a, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.amount").value(3000))
                .andExpect(jsonPath("$.expenseDate").value("2026-10-12"))
                .andExpect(jsonPath("$.category").value("HEALTH"))
                .andExpect(jsonPath("$.memo").hasJsonPath())
                .andExpect(jsonPath("$.memo").isEmpty());
    }

    @Test
    void otherUsersExpenseIsIndistinguishableFromMissing() throws Exception {
        String a = accessToken("a");
        String b = accessToken("b");
        create(a, body(3000, "2026-10-12", "HEALTH", "원래")).andExpect(status().isCreated());

        String missing = expectNotFound(getOne(b, 999));
        assertThat(expectNotFound(getOne(b, 1))).isEqualTo(missing);
        assertThat(expectNotFound(update(b, 1, "{\"amount\":1}"))).isEqualTo(missing);
        assertThat(expectNotFound(update(b, 999, "{\"amount\":1}"))).isEqualTo(missing);
        assertThat(expectNotFound(remove(b, 1))).isEqualTo(missing);
        assertThat(expectNotFound(remove(b, 999))).isEqualTo(missing);

        getOne(a, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(3000))
                .andExpect(jsonPath("$.memo").value("원래"));
    }

    @Test
    void nonNumericExpenseIdIsInvalidRequest() throws Exception {
        String a = accessToken("a");

        getOne(a, "abc").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void patchKeepsAbsentFieldsAndUpdatesTimestamp() throws Exception {
        String a = accessToken("a");
        create(a, body(3000, "2026-10-12", "HEALTH", "병원"));
        clock.advance(Duration.ofMinutes(1));

        update(a, 1, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(3000))
                .andExpect(jsonPath("$.expenseDate").value("2026-10-12"))
                .andExpect(jsonPath("$.category").value("HEALTH"))
                .andExpect(jsonPath("$.memo").value("병원"));
        update(a, 1, "{\"amount\":2500}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.amount").value(2500))
                .andExpect(jsonPath("$.category").value("HEALTH"))
                .andExpect(jsonPath("$.memo").value("병원"));
        update(a, 1, "{\"category\":\"LIVING\",\"memo\":\"생활\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(2500))
                .andExpect(jsonPath("$.category").value("LIVING"))
                .andExpect(jsonPath("$.memo").value("생활"));

        Map<String, Object> row = jdbc.queryForMap("SELECT created_at, updated_at FROM expenses WHERE id = 1");
        assertThat(row.get("updated_at").toString()).isNotEqualTo(row.get("created_at").toString());
        assertThat(jdbc.queryForObject("SELECT updated_at FROM expenses WHERE id = 1", OffsetDateTime.class).toInstant())
                .isEqualTo(Instant.parse("2026-10-13T00:01:00Z"));
    }

    @Test
    void patchMemoNullOrBlankDeletesMemo() throws Exception {
        String a = accessToken("a");
        create(a, body(3000, "2026-10-12", "HEALTH", "병원"));
        create(a, body(3000, "2026-10-12", "HEALTH", "병원"));

        expectNullMemo(update(a, 1, "{\"memo\":null}").andExpect(status().isOk()))
                .andExpect(jsonPath("$.category").value("HEALTH"));
        expectNullMemo(update(a, 2, "{\"memo\":\"  \"}").andExpect(status().isOk()));
        expectNullMemo(getOne(a, 1));
    }

    @Test
    void patchRejectsExplicitNullForRequiredFieldsAndInvalidValues() throws Exception {
        String a = accessToken("a");
        create(a, body(3000, "2026-10-12", "HEALTH", "병원"));

        String nullAmount = update(a, 1, "{\"amount\":null}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields", hasSize(1))).andReturn().getResponse().getContentAsString();
        assertThat(messagesOf(nullAmount, "amount")).containsOnly("1 이상 100000000 이하의 정수여야 합니다.");
        String nullDate = update(a, 1, "{\"expenseDate\":null}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields", hasSize(1))).andReturn().getResponse().getContentAsString();
        assertThat(messagesOf(nullDate, "expenseDate")).containsOnly("YYYY-MM-DD 형식이어야 합니다.");
        String nullCategory = update(a, 1, "{\"category\":null}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields", hasSize(1))).andReturn().getResponse().getContentAsString();
        assertThat(messagesOf(nullCategory, "category")).containsOnly("허용되지 않는 카테고리입니다.");
        expectInvalid(update(a, 1, "{\"amount\":0}"), "amount");
        expectInvalid(update(a, 1, "{\"expenseDate\":\"2026-10-14\"}"), "expenseDate");
        expectInvalid(update(a, 1, "{\"category\":\"COFFEE\"}"), "category");
        expectInvalid(update(a, 1, "{\"memo\":\"" + "a".repeat(101) + "\"}"), "memo");
        // 일부 필드가 잘못되면 다른 필드도 바꾸지 않는다.
        expectInvalid(update(a, 1, "{\"amount\":10,\"category\":null}"), "category");

        getOne(a, 1)
                .andExpect(jsonPath("$.amount").value(3000))
                .andExpect(jsonPath("$.expenseDate").value("2026-10-12"))
                .andExpect(jsonPath("$.category").value("HEALTH"))
                .andExpect(jsonPath("$.memo").value("병원"));
    }

    @Test
    void patchCanMoveExpenseToAnotherMonth() throws Exception {
        String a = accessToken("a");
        create(a, body(3000, "2026-10-01", "FOOD", null));

        update(a, 1, "{\"expenseDate\":\"2026-09-30\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expenseDate").value("2026-09-30"));

        list(a, "2026-10", "").andExpect(jsonPath("$.items").isEmpty());
        list(a, "2026-09", "").andExpect(jsonPath("$.items[*].id", contains(1)));
    }

    @Test
    void deleteRemovesOwnExpense() throws Exception {
        String a = accessToken("a");
        create(a, body(3000, "2026-10-12", "FOOD", null));

        remove(a, 1).andExpect(status().isNoContent()).andExpect(content().string(""));

        expectNotFound(getOne(a, 1));
        expectNotFound(remove(a, 1));
        assertThat(expenseCount()).isZero();
    }

    @Test
    void expenseEndpointsRequireAuthentication() throws Exception {
        expectUnauthorized(mvc.perform(post("/api/v1/expenses")
                .contentType(MediaType.APPLICATION_JSON).content(body(1, "2026-10-13", "FOOD", null))));
        expectUnauthorized(mvc.perform(get("/api/v1/months/2026-10/expenses")));
        expectUnauthorized(mvc.perform(get("/api/v1/expenses/1")));
        expectUnauthorized(mvc.perform(patch("/api/v1/expenses/1")
                .contentType(MediaType.APPLICATION_JSON).content("{}")));
        expectUnauthorized(mvc.perform(delete("/api/v1/expenses/1")));
        expectUnauthorized(getOne("garbage", 1));
    }

    @Test
    void listPagesByDateThenIdDescendingAcrossTies() throws Exception {
        String a = accessToken("a");
        accessToken("b");
        seed(1, "2026-10-05"); // 1
        seed(1, "2026-10-05"); // 2
        seed(1, "2026-10-05"); // 3
        seed(1, "2026-10-03"); // 4
        seed(1, "2026-10-07"); // 5
        seed(2, "2026-10-06"); // 6 다른 사용자
        seed(1, "2026-09-30"); // 7 이전 월
        seed(1, "2026-10-01"); // 8
        seed(1, "2026-10-31"); // 9

        String page1 = list(a, "2026-10", "&limit=3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(9, 5, 3)))
                .andExpect(jsonPath("$.items[0].expenseDate").value("2026-10-31"))
                .andExpect(jsonPath("$.items[0].amount").value(1000))
                .andExpect(jsonPath("$.items[0].category").value("FOOD"))
                .andExpect(jsonPath("$.items[0].memo").isEmpty())
                .andExpect(jsonPath("$.items[0].createdAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String cursor1 = JsonPath.read(page1, "$.nextCursor");
        assertThat(cursor1).isNotBlank();

        String page2 = list(a, "2026-10", "&limit=3&cursor=" + cursor1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(2, 1, 4)))
                .andReturn().getResponse().getContentAsString();
        String cursor2 = JsonPath.read(page2, "$.nextCursor");

        list(a, "2026-10", "&limit=3&cursor=" + cursor2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(8)))
                .andExpect(jsonPath("$.nextCursor").hasJsonPath())
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    void lastPageThatExactlyFillsLimitHasNullCursor() throws Exception {
        String a = accessToken("a");
        seed(1, "2026-10-01");
        seed(1, "2026-10-02");

        list(a, "2026-10", "&limit=2")
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    void listDefaultLimitIs20AndMaximumIs50() throws Exception {
        String a = accessToken("a");
        for (int i = 0; i < 51; i++) {
            seed(1, "2026-10-01");
        }

        String page1 = list(a, "2026-10", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(20)))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(page1, "$.nextCursor")).isNotBlank();

        list(a, "2026-10", "&limit=50").andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(50)));
        list(a, "2026-10", "&limit=1").andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)));
        for (String limit : List.of("0", "51", "-1", "abc")) {
            list(a, "2026-10", "&limit=" + limit)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void emptyAndPastMonthsReturnEmptyList() throws Exception {
        String a = accessToken("a");

        for (String month : List.of("2026-10", "2026-09", "2019-01")) {
            list(a, month, "")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isArray())
                    .andExpect(jsonPath("$.items").isEmpty())
                    .andExpect(jsonPath("$.nextCursor").hasJsonPath())
                    .andExpect(jsonPath("$.nextCursor").isEmpty());
        }
    }

    @Test
    void listRejectsMalformedAndFutureMonth() throws Exception {
        String a = accessToken("a");

        for (String month : List.of("2026-11", "2027-01", "2026-13", "2026-1", "202610", "abcd-ef", "2026-10-01")) {
            list(a, month, "")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void currentMonthFollowsSeoulAtMonthBoundary() throws Exception {
        // 2026-10-31T14:59Z = 서울 2026-10-31 23:59 → 11월은 아직 미래
        clock.set(Instant.parse("2026-10-31T14:59:00Z"));
        String a = accessToken("a");
        create(a, body(1, "2026-10-31", "FOOD", null)).andExpect(status().isCreated());
        list(a, "2026-11", "").andExpect(status().isBadRequest());

        // 2026-10-31T15:30Z = 서울 2026-11-01 00:30 (UTC는 아직 10월)
        clock.set(Instant.parse("2026-10-31T15:30:00Z"));
        a = accessToken("a");
        create(a, body(2, "2026-11-01", "FOOD", null)).andExpect(status().isCreated());

        list(a, "2026-11", "").andExpect(status().isOk()).andExpect(jsonPath("$.items[*].id", contains(2)));
        list(a, "2026-10", "").andExpect(status().isOk()).andExpect(jsonPath("$.items[*].id", contains(1)));
    }

    @Test
    void listRejectsMalformedCursor() throws Exception {
        String a = accessToken("a");
        seed(1, "2026-10-01");

        for (String cursor : List.of("!!!", "abc", "MTo", "MToyMDI2LTEwOjIwMjYtMTAtMDE6eA")) {
            list(a, "2026-10", "&cursor=" + cursor)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        }
    }

    @Test
    void cursorIsBoundToUserAndMonth() throws Exception {
        String a = accessToken("a");
        String b = accessToken("b");
        seed(1, "2026-10-01");
        seed(1, "2026-10-02");
        seed(2, "2026-10-01");
        seed(2, "2026-10-02");

        String page = list(a, "2026-10", "&limit=1").andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page, "$.nextCursor");

        list(b, "2026-10", "&limit=1&cursor=" + cursor)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        list(a, "2026-09", "&limit=1&cursor=" + cursor)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        list(a, "2026-10", "&limit=1&cursor=" + cursor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(1)));
    }

    @Test
    void listNeverShowsOtherUsersExpenses() throws Exception {
        String a = accessToken("a");
        String b = accessToken("b");
        seed(1, "2026-10-01");
        seed(2, "2026-10-01");
        seed(2, "2026-10-02");

        list(a, "2026-10", "").andExpect(jsonPath("$.items[*].id", contains(1)));
        list(b, "2026-10", "").andExpect(jsonPath("$.items[*].id", contains(3, 2)));
    }

    @Test
    void amountUpperBoundIsOneHundredMillion() throws Exception {
        String a = accessToken("a");

        create(a, body(100_000_000, "2026-10-13", "FOOD", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(100_000_000));
        expectInvalid(create(a, body(100_000_001, "2026-10-13", "FOOD", null)), "amount");
        expectInvalid(update(a, 1, "{\"amount\":100000001}"), "amount");
        update(a, 1, "{\"amount\":100000000}").andExpect(status().isOk());
    }

    @Test
    void expenseDateLowerBoundIs2000() throws Exception {
        String a = accessToken("a");

        create(a, body(1, "2000-01-01", "FOOD", null)).andExpect(status().isCreated());
        expectInvalid(create(a, body(1, "1999-12-31", "FOOD", null)), "expenseDate");
        expectInvalid(create(a, body(1, "0000-01-01", "FOOD", null)), "expenseDate");
        expectInvalid(update(a, 1, "{\"expenseDate\":\"1999-12-31\"}"), "expenseDate");
        assertThat(expenseCount()).isEqualTo(1);
    }

    @Test
    void listRejectsMonthBefore2000() throws Exception {
        String a = accessToken("a");

        list(a, "2000-01", "").andExpect(status().isOk());
        for (String month : List.of("1999-12", "0000-01")) {
            list(a, month, "")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.fields[0].field").value("month"));
        }
    }

    @Test
    void memoWithNulCharacterIsRejected() throws Exception {
        String a = accessToken("a");
        create(a, body(1, "2026-10-13", "FOOD", "원래")).andExpect(status().isCreated());

        expectInvalid(create(a, body(1, "2026-10-13", "FOOD", "a\\u0000b")), "memo");
        expectInvalid(update(a, 1, "{\"memo\":\"a\\u0000b\"}"), "memo");
        getOne(a, 1).andExpect(jsonPath("$.memo").value("원래"));
    }

    @Test
    void patchRejectsSeoulTomorrowWhileUtcIsStillYesterday() throws Exception {
        // 2026-10-13T15:30Z = 서울 2026-10-14 00:30
        clock.set(Instant.parse("2026-10-13T15:30:00Z"));
        String a = accessToken("a");
        create(a, body(1, "2026-10-01", "FOOD", null)).andExpect(status().isCreated());

        update(a, 1, "{\"expenseDate\":\"2026-10-14\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expenseDate").value("2026-10-14"));
        expectInvalid(update(a, 1, "{\"expenseDate\":\"2026-10-15\"}"), "expenseDate");
    }

    @Test
    void listRejectsCursorWhoseDateIsOutsideMonth() throws Exception {
        String a = accessToken("a");
        seed(1, "2026-10-01");

        for (String raw : List.of("1:2026-10:+999999999-12-31:1", "1:2026-10:2026-12-31:1",
                "1:2026-10:2026-09-30:1", "1:2026-10:2026-11-01:1")) {
            list(a, "2026-10", "&cursor=" + Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(raw.getBytes(StandardCharsets.UTF_8)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        }
    }

    @Test
    void createAfterUserRowDeletedIsUnauthorized() throws Exception {
        accessToken("a");
        // 인증 통과 직후 동시 탈퇴로 사용자 행이 사라진 상황. HTTP는 인증에서 먼저 거절되므로 서비스를 직접 호출한다.
        jdbc.update("DELETE FROM users WHERE id = 1");

        assertThatThrownBy(() -> expenseService.create(1,
                        new ExpenseRequest(1000L, "2026-10-13", null, null), null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(401);
                    assertThat(e.code()).isEqualTo("UNAUTHORIZED");
                });
        assertThat(expenseCount()).isZero();
    }

    @Test
    void createReportsAllInvalidFields() throws Exception {
        String a = accessToken("a");

        String json = create(a, "{\"amount\":0,\"expenseDate\":\"x\",\"category\":\"X\",\"memo\":\"\\u0000\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다."))
                .andReturn().getResponse().getContentAsString();
        assertThat(messagesOf(json, "amount")).containsOnly("1 이상 100000000 이하의 정수여야 합니다.");
        assertThat(messagesOf(json, "expenseDate")).containsOnly("YYYY-MM-DD 형식이어야 합니다.");
        assertThat(messagesOf(json, "category")).containsOnly("허용되지 않는 카테고리입니다.");
        assertThat(messagesOf(json, "memo")).containsOnly("허용되지 않는 문자가 있습니다.");

        // 날짜 범위와 메모 길이는 형식과 다른 문구다.
        String range = create(a, "{\"amount\":1,\"expenseDate\":\"2026-10-14\",\"memo\":\"" + "a".repeat(101) + "\"}")
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(messagesOf(range, "expenseDate")).containsOnly("2000-01-01부터 오늘까지만 허용합니다.");
        assertThat(messagesOf(range, "memo")).containsOnly("최대 100자입니다.");

        // 잘못된 본문이 잘못된 Idempotency-Key보다 먼저다.
        String withBadKey = mvc.perform(post("/api/v1/expenses").header("Authorization", "Bearer " + a)
                .header("Idempotency-Key", "bad").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":0,\"expenseDate\":\"2026-10-13\"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(messagesOf(withBadKey, "amount")).isNotEmpty();
        assertThat(messagesOf(withBadKey, "Idempotency-Key")).isEmpty();
        assertThat(expenseCount()).isZero();
    }

    // 필드 하나만 틀리면 fields 항목도 하나다. FIELD·TYPE_USE 둘에 선언한 제약이 중복 보고되지 않는지 고정한다.
    @Test
    void singleInvalidFieldIsReportedOnce() throws Exception {
        String a = accessToken("a");

        for (String json : List.of(
                "{\"amount\":0,\"expenseDate\":\"2026-10-13\",\"category\":\"FOOD\",\"memo\":\"m\"}",
                "{\"amount\":1,\"expenseDate\":\"x\",\"category\":\"FOOD\",\"memo\":\"m\"}",
                "{\"amount\":1,\"expenseDate\":\"2026-10-13\",\"category\":\"X\",\"memo\":\"m\"}",
                "{\"amount\":1,\"expenseDate\":\"2026-10-13\",\"category\":\"FOOD\",\"memo\":\"\\u0000\"}")) {
            create(a, json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields", hasSize(1)));
        }
    }

    @Test
    void createRejectsLooseValues() throws Exception {
        String a = accessToken("a");
        String typeError = "형식이 올바르지 않습니다.";

        for (String amount : List.of("\"5000\"", "1.5", "true")) {
            create(a, "{\"amount\":" + amount + ",\"expenseDate\":\"2026-10-13\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields", hasSize(1)))
                    .andExpect(jsonPath("$.fields[0].field").value("amount"))
                    .andExpect(jsonPath("$.fields[0].message").value(typeError));
        }
        create(a, "{\"amount\":1,\"expenseDate\":\"2026-10-13\",\"memo\":1234}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields", hasSize(1)))
                .andExpect(jsonPath("$.fields[0].field").value("memo"))
                .andExpect(jsonPath("$.fields[0].message").value(typeError));
        assertThat(expenseCount()).isZero();

        create(a, "{\"amount\":1,\"expenseDate\":\"2026-10-13\",\"memo\":\"1234\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memo").value("1234"));
    }

    @Test
    void patchChecksBodyBeforeExistence() throws Exception {
        String a = accessToken("a");
        update(a, 99, "[]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("요청 본문을 읽을 수 없습니다."))
                .andExpect(jsonPath("$.fields").doesNotExist());
        expectInvalid(update(a, 99, "{\"amount\":0}"), "amount");
        expectNotFound(update(a, 99, "{\"amount\":1}"));
        create(a, body(3000, "2026-10-12", "HEALTH", null));
        String json = update(a, 1, "{\"amount\":0,\"category\":\"X\"}").andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(messagesOf(json, "amount")).containsOnly("1 이상 100000000 이하의 정수여야 합니다.");
        assertThat(messagesOf(json, "category")).containsOnly("허용되지 않는 카테고리입니다.");
        String both = update(a, 1, "{\"expenseDate\":\"2026-10-14\",\"memo\":\"\\u0000\"}")
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(messagesOf(both, "expenseDate")).containsOnly("2000-01-01부터 오늘까지만 허용합니다.");
        assertThat(messagesOf(both, "memo")).containsOnly("허용되지 않는 문자가 있습니다.");
    }

    @Test
    void patchRejectsLooseValues() throws Exception {
        String a = accessToken("a");
        String typeError = "형식이 올바르지 않습니다.";
        create(a, body(3000, "2026-10-12", "HEALTH", "병원"));

        for (String json : List.of("{\"amount\":\"5000\"}", "{\"amount\":1.5}", "{\"amount\":true}",
                "{\"memo\":1234}", "{\"expenseDate\":20261012}", "{\"category\":1}")) {
            String field = json.substring(2, json.indexOf('"', 2));
            update(a, 1, json).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다."))
                    .andExpect(jsonPath("$.fields", hasSize(1)))
                    .andExpect(jsonPath("$.fields[0].field").value(field))
                    .andExpect(jsonPath("$.fields[0].message").value(typeError));
        }
        getOne(a, 1)
                .andExpect(jsonPath("$.amount").value(3000))
                .andExpect(jsonPath("$.memo").value("병원"));
        update(a, 1, "{\"memo\":\"1234\"}").andExpect(status().isOk()).andExpect(jsonPath("$.memo").value("1234"));
    }

    // PATCH는 보낸 열만 쓰므로, 동시에 커밋된 다른 열 변경을 옛 값으로 덮지 않고 응답도 저장된 행을 보여 준다.
    @Test
    void concurrentPatchDoesNotOverwriteCommittedChange() throws Exception {
        String a = accessToken("a");
        create(a, body(3000, "2026-10-12", "HEALTH", "old")).andExpect(status().isCreated());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection other = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword())) {
            other.setAutoCommit(false);
            int otherPid;
            try (PreparedStatement ps = other.prepareStatement("SELECT pg_backend_pid()");
                    ResultSet rs = ps.executeQuery()) {
                rs.next();
                otherPid = rs.getInt(1);
            }
            try (PreparedStatement ps = other.prepareStatement("UPDATE expenses SET amount = 7000 WHERE id = 1")) {
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            Future<MockHttpServletResponse> patch = pool.submit(() -> update(a, 1, "{\"memo\":\"new\"}")
                    .andReturn().getResponse());
            awaitBlockedBy(otherPid);
            other.commit();

            MockHttpServletResponse response = patch.get(30, TimeUnit.SECONDS);
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat((Integer) JsonPath.read(response.getContentAsString(), "$.amount")).isEqualTo(7000);
            assertThat((String) JsonPath.read(response.getContentAsString(), "$.memo")).isEqualTo("new");
        } finally {
            // 실패 경로에서도 PATCH가 끝난 뒤 다음 테스트로 넘어간다(other를 닫으면 롤백되어 잠금이 풀린다).
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        Map<String, Object> row = jdbc.queryForMap("SELECT amount, memo FROM expenses WHERE id = 1");
        assertThat(row.get("amount")).isEqualTo(7000L);
        assertThat(row.get("memo")).isEqualTo("new");
    }

    // blockerPid가 잡은 잠금을 기다리는 세션이 보일 때까지 폴링한다(다른 대기 세션과 혼동하지 않도록 차단자로 좁힌다).
    private void awaitBlockedBy(int blockerPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            // 이 클래스는 자동 커밋이라 통계 스냅샷이 남지 않지만, 트랜잭션 안에서 불려도 최신 목록을 보도록 방어로 비운다.
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND pg_blocking_pids(pid) @> ARRAY[?]
                    """, Integer.class, blockerPid);
            if (waiting != null && waiting >= 1) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new IllegalStateException("PATCH가 잠금 대기에 들어가지 않았다.");
    }

    @Test
    void categoryFilterReturnsOnlyThatCategoryInListOrder() throws Exception {
        String a = accessToken("a");
        seed(1, "2026-10-05", "CAFE"); // 1
        seed(1, "2026-10-10", "CAFE"); // 2
        seed(1, "2026-10-08", "FOOD"); // 3
        seed(1, "2026-10-10", "CAFE"); // 4
        seed(1, "2026-10-12", "FOOD"); // 5
        seed(1, "2026-09-20", "CAFE"); // 6 이전 월

        list(a, "2026-10", "&category=CAFE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(4, 2, 1)))
                .andExpect(jsonPath("$.items[*].category", contains("CAFE", "CAFE", "CAFE")))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    void categoryFilterPagesWithCursor() throws Exception {
        String a = accessToken("a");
        seed(1, "2026-10-01", "CAFE"); // 1
        seed(1, "2026-10-02", "FOOD"); // 2
        seed(1, "2026-10-03", "CAFE"); // 3
        seed(1, "2026-10-04", "FOOD"); // 4
        seed(1, "2026-10-05", "CAFE"); // 5
        seed(1, "2026-10-06", "FOOD"); // 6

        String page1 = list(a, "2026-10", "&category=CAFE&limit=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(5, 3)))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page1, "$.nextCursor");

        list(a, "2026-10", "&category=CAFE&limit=2&cursor=" + cursor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(1)))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    void categoryFilterExcludesOtherUsers() throws Exception {
        String a = accessToken("a");
        accessToken("b");
        seed(1, "2026-10-01", "CAFE"); // 1
        seed(2, "2026-10-02", "CAFE"); // 2

        list(a, "2026-10", "&category=CAFE").andExpect(jsonPath("$.items[*].id", contains(1)));
    }

    @Test
    void invalidCategoryFilterIsRejected() throws Exception {
        String a = accessToken("a");
        seed(1, "2026-10-01", "CAFE");

        for (String category : List.of("cafe", "COFFEE", "")) {
            expectInvalid(list(a, "2026-10", "&category=" + category), "category");
        }
        expectInvalid(list(a, "2026-11", "&category=cafe"), "month");
        expectInvalid(list(a, "2026-10", "&limit=0&category=cafe"), "limit");
    }

    @Test
    void dateFilterReturnsOnlyThatDayInListOrder() throws Exception {
        String a = accessToken("a");
        seed(1, "2026-10-10"); // 1
        seed(1, "2026-10-09"); // 2
        seed(1, "2026-10-10"); // 3
        seed(1, "2026-10-11"); // 4

        list(a, "2026-10", "&date=2026-10-10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(3, 1)))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    void dateFilterPagesWithCursor() throws Exception {
        String a = accessToken("a");
        seed(1, "2026-10-10"); // 1
        seed(1, "2026-10-10"); // 2
        seed(1, "2026-10-10"); // 3

        String page1 = list(a, "2026-10", "&date=2026-10-10&limit=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(3, 2)))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page1, "$.nextCursor");

        list(a, "2026-10", "&date=2026-10-10&limit=2&cursor=" + cursor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(1)))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    void dateFilterCombinesWithCategory() throws Exception {
        String a = accessToken("a");
        seed(1, "2026-10-10", "CAFE"); // 1
        seed(1, "2026-10-10", "FOOD"); // 2
        seed(1, "2026-10-11", "CAFE"); // 3

        list(a, "2026-10", "&date=2026-10-10&category=CAFE")
                .andExpect(jsonPath("$.items[*].id", contains(1)));
    }

    @Test
    void dateFilterExcludesOtherUsers() throws Exception {
        String a = accessToken("a");
        accessToken("b");
        seed(1, "2026-10-10"); // 1
        seed(2, "2026-10-10"); // 2

        list(a, "2026-10", "&date=2026-10-10").andExpect(jsonPath("$.items[*].id", contains(1)));
    }

    @Test
    void futureDateInCurrentMonthReturnsEmptyList() throws Exception {
        String a = accessToken("a");
        seed(1, "2026-10-13");

        list(a, "2026-10", "&date=2026-10-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void dateFilterOnLastDayOfMonthExcludesNextMonth() throws Exception {
        String a = accessToken("a");
        seed(1, "2026-09-30"); // 1
        seed(1, "2026-09-30"); // 2
        seed(1, "2026-10-01"); // 3

        list(a, "2026-09", "&date=2026-09-30")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(2, 1)))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    void invalidDateFilterIsRejected() throws Exception {
        String a = accessToken("a");

        for (String date : List.of("", "2026-10-1", "%202026-10-01", "2026/10/01", "2026-09-30")) {
            expectInvalid(list(a, "2026-10", "&date=" + date), "date");
        }
        expectInvalid(list(a, "2026-02", "&date=2026-02-30"), "date");
    }

    @Test
    void dateFilterValidationOrder() throws Exception {
        String a = accessToken("a");

        expectInvalid(list(a, "2026-10", "&category=cafe&date=bad"), "category");
        expectInvalid(list(a, "2026-10", "&limit=0&date=bad"), "limit");
        expectInvalid(list(a, "2026-10", "&date=bad&cursor=bad"), "date");
        expectInvalid(list(a, "2026-11", "&date=bad"), "month");
    }

    private void seed(long userId, String date) {
        seed(userId, date, "FOOD");
    }

    private void seed(long userId, String date, String category) {
        jdbc.update("INSERT INTO expenses (user_id, amount, expense_date, category) VALUES (?, 1000, ?::date, ?)",
                userId, date, category);
    }

    private int expenseCount() {
        return jdbc.queryForObject("SELECT count(*) FROM expenses", Integer.class);
    }

    private static String body(long amount, String date, String category, String memo) {
        return "{\"amount\":" + amount + ",\"expenseDate\":\"" + date + "\",\"category\":\"" + category + "\""
                + (memo == null ? "" : ",\"memo\":\"" + memo + "\"") + "}";
    }

    private String accessToken(String id) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"ok:" + id + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private ResultActions create(String token, String json) throws Exception {
        return mvc.perform(post("/api/v1/expenses").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions getOne(String token, Object id) throws Exception {
        return mvc.perform(get("/api/v1/expenses/" + id).header("Authorization", "Bearer " + token));
    }

    private ResultActions update(String token, Object id, String json) throws Exception {
        return mvc.perform(patch("/api/v1/expenses/" + id).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions remove(String token, Object id) throws Exception {
        return mvc.perform(delete("/api/v1/expenses/" + id).header("Authorization", "Bearer " + token));
    }

    private ResultActions list(String token, String month, String query) throws Exception {
        return mvc.perform(get("/api/v1/months/" + month + "/expenses?x=" + query)
                .header("Authorization", "Bearer " + token));
    }

    private static ResultActions expectNullMemo(ResultActions result) throws Exception {
        return result.andExpect(jsonPath("$.memo").hasJsonPath()).andExpect(jsonPath("$.memo").isEmpty());
    }

    private static List<String> messagesOf(String json, String field) {
        return JsonPath.read(json, "$.fields[?(@.field == '" + field + "')].message");
    }

    private static void expectInvalid(ResultActions result, String field) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fields[0].field").value(field));
    }

    private static String expectNotFound(ResultActions result) throws Exception {
        return result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXPENSE_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
    }

    private static void expectUnauthorized(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
