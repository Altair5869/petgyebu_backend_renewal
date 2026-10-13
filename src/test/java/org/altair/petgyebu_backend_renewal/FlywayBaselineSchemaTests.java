package org.altair.petgyebu_backend_renewal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
class FlywayBaselineSchemaTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE expenses, monthly_budgets, users RESTART IDENTITY");
    }

    @Test
    void createsOnlyTheThreeMvpTables() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
                """, String.class);

        assertThat(tables).containsExactlyInAnyOrder("users", "expenses", "monthly_budgets");
    }

    @Test
    void columnsMatchDesignedTypesAndNullability() {
        assertThat(columns("users")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "id", "bigint NO",
                "provider", "character varying NO",
                "provider_user_id", "character varying NO",
                "pet_type", "character varying YES",
                "created_at", "timestamp with time zone NO",
                "last_login_time", "timestamp with time zone NO",
                "active_session_id", "uuid YES",
                "refresh_token_hash", "character varying YES",
                "refresh_token_expires_at", "timestamp with time zone YES"));

        assertThat(columns("expenses")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "id", "bigint NO",
                "user_id", "bigint NO",
                "amount", "bigint NO",
                "expense_date", "date NO",
                "category", "character varying NO",
                "memo", "text YES",
                "created_at", "timestamp with time zone NO",
                "updated_at", "timestamp with time zone NO"));

        assertThat(columns("monthly_budgets")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "id", "bigint NO",
                "user_id", "bigint NO",
                "budget_month", "date NO",
                "amount", "bigint NO",
                "created_at", "timestamp with time zone NO",
                "updated_at", "timestamp with time zone NO"));
    }

    @Test
    void rejectsDuplicateProviderIdentity() {
        insertUser("KAKAO", "same-id");
        insertUser("GOOGLE", "same-id");

        assertThatThrownBy(() -> insertUser("KAKAO", "same-id"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsSecondBudgetForSameUserAndMonth() {
        long userId = insertUser("KAKAO", "u1");
        insertBudget(userId, "2026-10-01");
        insertBudget(userId, "2026-11-01");

        assertThatThrownBy(() -> insertBudget(userId, "2026-10-01"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsDuplicateExpenses() {
        long userId = insertUser("KAKAO", "u1");
        insertExpense(userId);
        insertExpense(userId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM expenses", Long.class)).isEqualTo(2);
    }

    @Test
    void rejectsRowsForMissingUser() {
        assertThatThrownBy(() -> insertExpense(999L))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertBudget(999L, "2026-10-01"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void doesNotCascadeUserDeletion() {
        long withExpense = insertUser("KAKAO", "u1");
        insertExpense(withExpense);
        long withBudget = insertUser("KAKAO", "u2");
        insertBudget(withBudget, "2026-10-01");

        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", withExpense))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", withBudget))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void hasNoCheckConstraints() {
        Long checks = jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                WHERE c.contype = 'c' AND t.relname IN ('users', 'expenses', 'monthly_budgets')
                """, Long.class);

        assertThat(checks).isZero();
    }

    @Test
    void indexesExpensesForMonthlyListing() {
        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_expenses_user_date_id'",
                String.class);

        assertThat(definition).contains("ON public.expenses USING btree (user_id, expense_date DESC, id DESC)");
    }

    private Map<String, String> columns(String table) {
        return jdbc.query("""
                SELECT column_name, data_type, is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                """, rs -> {
            Map<String, String> result = new HashMap<>();
            while (rs.next()) {
                result.put(rs.getString("column_name"),
                        rs.getString("data_type") + " " + rs.getString("is_nullable"));
            }
            return result;
        }, table);
    }

    private long insertUser(String provider, String providerUserId) {
        return jdbc.queryForObject("""
                INSERT INTO users (provider, provider_user_id, last_login_time)
                VALUES (?, ?, now()) RETURNING id
                """, Long.class, provider, providerUserId);
    }

    private void insertExpense(long userId) {
        jdbc.update("""
                INSERT INTO expenses (user_id, amount, expense_date, category)
                VALUES (?, 10000, DATE '2026-10-13', 'FOOD')
                """, userId);
    }

    private void insertBudget(long userId, String firstDay) {
        jdbc.update("""
                INSERT INTO monthly_budgets (user_id, budget_month, amount)
                VALUES (?, CAST(? AS DATE), 300000)
                """, userId, firstDay);
    }
}
