package org.altair.petgyebu_backend_renewal.budget;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

public interface MonthlyBudgetRepository extends Repository<MonthlyBudget, Long> {

    @Query("SELECT * FROM monthly_budgets WHERE user_id = :userId AND budget_month = :budgetMonth")
    Optional<MonthlyBudget> find(long userId, LocalDate budgetMonth);

    // 동시 PUT도 한 문장 안에서 생성 또는 교체되므로 409·재시도가 필요 없다.
    @Query("""
            INSERT INTO monthly_budgets (user_id, budget_month, amount, created_at, updated_at)
            VALUES (:userId, :budgetMonth, :amount, :now, :now)
            ON CONFLICT (user_id, budget_month)
            DO UPDATE SET amount = EXCLUDED.amount, updated_at = EXCLUDED.updated_at
            RETURNING *
            """)
    MonthlyBudget upsert(long userId, LocalDate budgetMonth, long amount, OffsetDateTime now);
}
