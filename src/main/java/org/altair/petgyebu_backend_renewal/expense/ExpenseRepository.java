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

    // PATCH 전용. set* 플래그가 true인 열만 바꾸고 나머지는 행의 최신 값을 그대로 둔다.
    // 한 문장이라 잠금 없이도 동시 수정이 서로 다른 열의 변경을 옛 값으로 덮지 않는다. 행이 없으면 빈 값이다.
    @Query("""
            UPDATE expenses
            SET amount = CASE WHEN :setAmount THEN :amount ELSE amount END,
                expense_date = CASE WHEN :setDate THEN :expenseDate ELSE expense_date END,
                category = CASE WHEN :setCategory THEN :category ELSE category END,
                memo = CASE WHEN :setMemo THEN :memo ELSE memo END,
                updated_at = :now
            WHERE id = :id AND user_id = :userId
            RETURNING *
            """)
    Optional<Expense> update(long id, long userId, boolean setAmount, Long amount, boolean setDate,
            LocalDate expenseDate, boolean setCategory, String category, boolean setMemo, String memo,
            OffsetDateTime now);

    @Modifying
    @Query("DELETE FROM expenses WHERE id = :id AND user_id = :userId")
    int delete(long id, long userId);

    // 탈퇴 전용. 사용자의 지출 전체를 지운다.
    @Modifying
    @Query("DELETE FROM expenses WHERE user_id = :userId")
    int deleteAllOfUser(long userId);

    // 사용자 [from, to)의 지출 건수. 월 지출 건수 상한 확인용(ix_expenses_user_date_id 사용).
    @Query("""
            SELECT count(*) FROM expenses
            WHERE user_id = :userId AND expense_date >= :from AND expense_date < :to
            """)
    long countInRange(long userId, LocalDate from, LocalDate to);

    // 사용자·월 [from, to)의 카테고리·금액 행. 홈과 월 통계가 Service에서 집계한다.
    @Query("""
            SELECT category, amount FROM expenses
            WHERE user_id = :userId AND expense_date >= :from AND expense_date < :to
            """)
    List<ExpenseAmount> findAmounts(long userId, LocalDate from, LocalDate to);

    // 사용자 [from, to)의 날짜·카테고리·금액 행. 인사이트가 Service에서 주간 비교와 월 합계를 계산한다.
    @Query("""
            SELECT expense_date, category, amount FROM expenses
            WHERE user_id = :userId AND expense_date >= :from AND expense_date < :to
            """)
    List<ExpenseDayAmount> findDayAmounts(long userId, LocalDate from, LocalDate to);

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

    // 카테고리 필터가 있는 첫 페이지. findFirstPage에 category 조건만 더했다.
    @Query("""
            SELECT * FROM expenses
            WHERE user_id = :userId AND expense_date >= :from AND expense_date < :to
              AND category = :category
            ORDER BY expense_date DESC, id DESC
            LIMIT :limit
            """)
    List<Expense> findFirstPageInCategory(long userId, LocalDate from, LocalDate to, String category, int limit);

    @Query("""
            SELECT * FROM expenses
            WHERE user_id = :userId AND expense_date >= :from AND expense_date < :to
              AND category = :category
              AND (expense_date, id) < (:cursorDate, :cursorId)
            ORDER BY expense_date DESC, id DESC
            LIMIT :limit
            """)
    List<Expense> findPageAfterInCategory(long userId, LocalDate from, LocalDate to, String category,
            LocalDate cursorDate, long cursorId, int limit);
}
