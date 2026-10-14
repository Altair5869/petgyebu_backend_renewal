package org.altair.petgyebu_backend_renewal.stats;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import org.altair.petgyebu_backend_renewal.budget.BudgetUsage;
import org.altair.petgyebu_backend_renewal.budget.MonthlyBudgetRepository;
import org.altair.petgyebu_backend_renewal.common.Months;
import org.altair.petgyebu_backend_renewal.expense.Category;
import org.altair.petgyebu_backend_renewal.expense.ExpenseRepository;
import org.altair.petgyebu_backend_renewal.expense.ExpenseRepository.CategoryTotal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatsService {

    // usage는 그 달 예산이 없으면 null이다.
    public record Stats(YearMonth month, long spentAmount, long expenseCount, BudgetUsage usage,
            List<CategoryStat> categories) {
    }

    public record CategoryStat(Category category, long spentAmount, long expenseCount, BigDecimal sharePercent) {
    }

    // 금액 내림차순, 동률은 Category 선언 순서(FOOD, CAFE, TRANSPORT, SHOPPING, HEALTH, LIVING, OTHER).
    private static final Comparator<CategoryTotal> ORDER = Comparator
            .comparingLong(CategoryTotal::spentAmount).reversed()
            .thenComparing(CategoryTotal::category);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final MonthlyBudgetRepository budgets;
    private final ExpenseRepository expenses;
    private final Clock clock;

    public StatsService(MonthlyBudgetRepository budgets, ExpenseRepository expenses, Clock clock) {
        this.budgets = budgets;
        this.expenses = expenses;
        this.clock = clock;
    }

    // 합계와 카테고리 쿼리가 같은 스냅숏을 보도록 REPEATABLE READ로 읽는다(카테고리 합 = 월 합계).
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Stats stats(long userId, String monthText) {
        YearMonth month = Months.parsePastOrCurrent(monthText, clock);
        LocalDate from = month.atDay(1);
        LocalDate to = month.plusMonths(1).atDay(1);
        long spent = expenses.sumAmount(userId, from, to);
        List<CategoryTotal> totals = expenses.sumByCategory(userId, from, to);
        long count = totals.stream().mapToLong(CategoryTotal::expenseCount).sum();
        List<CategoryStat> categories = totals.stream()
                .sorted(ORDER)
                .map(t -> new CategoryStat(t.category(), t.spentAmount(), t.expenseCount(), share(t.spentAmount(), spent)))
                .toList();
        BudgetUsage usage = budgets.find(userId, from).map(b -> new BudgetUsage(b.amount(), spent)).orElse(null);
        return new Stats(month, spent, count, usage, categories);
    }

    // 카테고리가 있으면 spent > 0이다. 소수 첫째 자리 반올림(HALF_UP), 합계 100 보정은 하지 않는다.
    private static BigDecimal share(long categorySpent, long spent) {
        return BigDecimal.valueOf(categorySpent).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(spent), 1, RoundingMode.HALF_UP);
    }
}
