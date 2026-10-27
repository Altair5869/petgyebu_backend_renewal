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

    // 탈퇴 전용. 사용자의 지출 전체를 지운다.
    @Modifying
    @Query("DELETE FROM expenses WHERE user_id = :userId")
    int deleteAllOfUser(long userId);

    // 사용자·월 [from, to)의 카테고리·금액 행. 홈과 월 통계가 Service에서 집계한다.
    @Query("""
            SELECT category, amount FROM expenses
            WHERE user_id = :userId AND expense_date >= :from AND expense_date < :to
            """)
    List<ExpenseAmount> findAmounts(long userId, LocalDate from, LocalDate to);

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
