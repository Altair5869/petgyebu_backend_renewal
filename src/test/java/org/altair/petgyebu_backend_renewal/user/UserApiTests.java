package org.altair.petgyebu_backend_renewal.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.node.JsonNodeFactory;

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

    // 회귀 방지용: 래치로 출발만 맞추며 트랜잭션이 실제로 겹친다는 보장은 없다.
    @Test
    void concurrentWithdrawalsOfSameUserAllCompleteAndDeleteOnlyCallersRows() throws Exception {
        login("kakao", "ok:k1");
        login("kakao", "ok:other");
        seedRows(1);
        seedRows(2);
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    userService.withdraw(1);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(counts(1)).containsExactly(0, 0, 0);
        assertThat(counts(2)).containsExactly(1, 2, 1);
    }

    @Test
    void withdrawingAlreadyWithdrawnUserCompletesWithoutError() throws Exception {
        login("kakao", "ok:k1");
        login("kakao", "ok:other");
        seedRows(1);
        seedRows(2);
        userService.withdraw(1);

        userService.withdraw(1);

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

    @Test
    void withdrawalRetriesWhenConcurrentInsertCausesForeignKeyViolation() throws Exception {
        String access = accessToken(login("kakao", "ok:k1"));
        login("kakao", "ok:other");
        seedRows(1);
        seedRows(2);
        // 첫 시도에서만 지출 삭제 뒤 같은 사용자의 지출이 끼어들어 사용자 삭제가 FK 위반으로 실패한다.
        // 시퀀스는 롤백되지 않으므로 1회 표시로 쓴다.
        createRacingInsertTrigger("nextval('withdraw_race_seq') = 1");
        try {
            withdraw(access).andExpect(status().isNoContent());
            assertThat(attempts()).isEqualTo(2);
        } finally {
            dropRacingInsertTrigger();
        }

        assertThat(counts(1)).containsExactly(0, 0, 0);
        assertThat(counts(2)).containsExactly(1, 2, 1);
    }

    @Test
    void withdrawalGivesUpAfterThreeForeignKeyViolationsAndKeepsAllRows() throws Exception {
        String access = accessToken(login("kakao", "ok:k1"));
        seedRows(1);
        createRacingInsertTrigger("nextval('withdraw_race_seq') > 0");
        try {
            withdraw(access)
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
            assertThat(attempts()).isEqualTo(3);
        } finally {
            dropRacingInsertTrigger();
        }

        assertThat(counts(1)).containsExactly(1, 2, 1);
    }

    @Test
    void withdrawalDoesNotRetryNonForeignKeyFailure() throws Exception {
        login("kakao", "ok:k1");
        jdbc.execute("CREATE SEQUENCE withdraw_race_seq");
        jdbc.execute("""
                CREATE FUNCTION fail_user_delete() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM nextval('withdraw_race_seq');
                RAISE EXCEPTION 'forced user delete failure' USING ERRCODE = '23505'; END $$""");
        jdbc.execute("""
                CREATE TRIGGER fail_user_delete BEFORE DELETE ON users
                FOR EACH ROW EXECUTE FUNCTION fail_user_delete()""");
        try {
            // FK가 아닌 무결성 오류(23505)는 DataIntegrityViolationException이어도 다시 시도하지 않는다.
            assertThatThrownBy(() -> userService.withdraw(1))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("forced user delete failure");
            assertThat(attempts()).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TRIGGER fail_user_delete ON users");
            jdbc.execute("DROP FUNCTION fail_user_delete()");
            jdbc.execute("DROP SEQUENCE withdraw_race_seq");
        }
    }

    @Test
    void selectPetIsReflectedInMe() throws Exception {
        String body = login("google", "ok:g1").andReturn().getResponse().getContentAsString();
        String access = JsonPath.read(body, "$.accessToken");
        String refresh = JsonPath.read(body, "$.refreshToken");

        selectPet(access, "{\"petType\":\"CAT\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.provider").value("GOOGLE"))
                .andExpect(jsonPath("$.petType").value("CAT"))
                .andExpect(jsonPath("$.length()").value(3));

        me(access).andExpect(status().isOk()).andExpect(jsonPath("$.petType").value("CAT"));
        // 펫 변경이 세션·리프레시 토큰 컬럼을 건드리지 않아 로그인이 유지된다.
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void changePetKeepsExpensesAndBudgetsAndOtherUsers() throws Exception {
        String access = accessToken(login("kakao", "ok:k1"));
        login("kakao", "ok:other");
        jdbc.update("UPDATE users SET pet_type = 'CAT'");
        seedRows(1);
        seedRows(2);
        String before = snapshot(1);

        selectPet(access, "{\"petType\":\"DOG\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.petType").value("DOG"));

        assertThat(petType(1)).isEqualTo("DOG");
        assertThat(petType(2)).isEqualTo("CAT");
        assertThat(snapshot(1)).isEqualTo(before);
        assertThat(counts(2)).containsExactly(1, 2, 1);
    }

    @Test
    void selectPetRejectsInvalidPetType() throws Exception {
        String access = accessToken(login("kakao", "ok:k1"));
        jdbc.update("UPDATE users SET pet_type = 'CAT' WHERE id = 1");

        for (String body : new String[] {"{}", "{\"petType\":null}", "{\"petType\":\"cat\"}",
                "{\"petType\":\"BIRD\"}", "{\"petType\":\"\"}", "{\"petType\":0}",
                "{\"petType\":[\"CAT\"]}"}) {
            selectPet(access, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.fields[0].field").value("petType"));
        }
        for (String body : new String[] {"[]", "\"CAT\"", "{"}) {
            selectPet(access, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertThat(petType(1)).isEqualTo("CAT");
    }

    @Test
    void selectPetRequiresAuthentication() throws Exception {
        expectUnauthorized(mvc.perform(put("/api/v1/users/me/pet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"petType\":\"CAT\"}")));
        expectUnauthorized(selectPet("garbage", "{\"petType\":\"CAT\"}"));
    }

    @Test
    void selectPetAfterRowVanishedIsUnauthorized() {
        assertThatThrownBy(() -> userService.selectPet(99, JsonNodeFactory.instance.objectNode().put("petType", "CAT")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("UNAUTHORIZED"));
    }

    // 사용자 삭제 직전(지출·예산 삭제 뒤)에 조건이 참이면 같은 사용자의 지출을 끼워 넣는다.
    private void createRacingInsertTrigger(String condition) {
        jdbc.execute("CREATE SEQUENCE withdraw_race_seq");
        jdbc.execute("""
                CREATE FUNCTION racing_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF %s THEN
                    INSERT INTO expenses (user_id, amount, expense_date, category)
                    VALUES (OLD.id, 500, '2026-10-03', 'FOOD');
                  END IF;
                  RETURN OLD;
                END $$""".formatted(condition));
        jdbc.execute("""
                CREATE TRIGGER racing_insert BEFORE DELETE ON users
                FOR EACH ROW EXECUTE FUNCTION racing_insert()""");
    }

    private void dropRacingInsertTrigger() {
        jdbc.execute("DROP TRIGGER racing_insert ON users");
        jdbc.execute("DROP FUNCTION racing_insert()");
        jdbc.execute("DROP SEQUENCE withdraw_race_seq");
    }

    // 트리거가 실행된 횟수 = 사용자 삭제 시도 횟수
    private long attempts() {
        return jdbc.queryForObject("SELECT last_value FROM withdraw_race_seq", Long.class);
    }

    private String petType(long userId) {
        return jdbc.queryForObject("SELECT pet_type FROM users WHERE id = ?", String.class, userId);
    }

    // 사용자의 지출·예산 행 전체 내용
    private String snapshot(long userId) {
        return jdbc.queryForList("SELECT * FROM expenses WHERE user_id = ? ORDER BY id", userId).toString()
                + jdbc.queryForList("SELECT * FROM monthly_budgets WHERE user_id = ? ORDER BY id", userId);
    }

    private ResultActions selectPet(String accessToken, String body) throws Exception {
        return mvc.perform(put("/api/v1/users/me/pet")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
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
