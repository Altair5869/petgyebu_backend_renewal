package org.altair.petgyebu_backend_renewal.expense;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

public interface ExpenseRepository extends Repository<Expense, Long> {

    Expense save(Expense expense);

    Optional<Expense> findByIdAndUserId(long id, long userId);

    Optional<Expense> findByUserIdAndIdempotencyKey(long userId, UUID idempotencyKey);

    @Modifying
    @Query("""
            UPDATE expenses
            SET amount = :amount, expense_date = :expenseDate, category = :category, memo = :memo, updated_at = :now
            WHERE id = :id AND user_id = :userId
            """)
    int update(long id, long userId, long amount, LocalDate expenseDate, String category, String memo,
            OffsetDateTime now);

    @Modifying
    @Query("DELETE FROM expenses WHERE id = :id AND user_id = :userId")
    int delete(long id, long userId);

    // 사용자·월 [from, to)의 지출 합계. 홈과 월 통계가 공유하며 지출이 없으면 0이다.
    @Query("""
            SELECT COALESCE(SUM(amount), 0) FROM expenses
            WHERE user_id = :userId AND expense_date >= :from AND expense_date < :to
            """)
    long sumAmount(long userId, LocalDate from, LocalDate to);

    record CategoryTotal(Category category, long spentAmount, long expenseCount) {
    }

    // 사용자·월 [from, to)의 카테고리별 합계·건수. 지출이 있는 카테고리만 나온다.
    @Query("""
            SELECT category, SUM(amount) AS spent_amount, COUNT(*) AS expense_count FROM expenses
            WHERE user_id = :userId AND expense_date >= :from AND expense_date < :to
            GROUP BY category
            """)
    List<CategoryTotal> sumByCategory(long userId, LocalDate from, LocalDate to);

    @Query("""
            SELECT * FROM expenses
            WHERE user_id = :userId AND expense_date >= :from AND expense_date < :to
            ORDER BY expense_date DESC, id DESC
            LIMIT :limit
            """)
    List<Expense> findFirstPage(long userId, LocalDate from, LocalDate to, int limit);

    @Query("""
            SELECT * FROM expenses
            WHERE user_id = :userId AND expense_date >= :from AND expense_date < :to
              AND (expense_date, id) < (:cursorDate, :cursorId)
            ORDER BY expense_date DESC, id DESC
            LIMIT :limit
            """)
    List<Expense> findPageAfter(long userId, LocalDate from, LocalDate to, LocalDate cursorDate, long cursorId,
            int limit);
}
