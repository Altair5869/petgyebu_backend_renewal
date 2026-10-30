package org.altair.petgyebu_backend_renewal.stats;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import org.altair.petgyebu_backend_renewal.budget.BudgetUsage;
import org.altair.petgyebu_backend_renewal.budget.MonthlyBudgetRepository;
import org.altair.petgyebu_backend_renewal.common.Months;
import org.altair.petgyebu_backend_renewal.expense.Category;
import org.altair.petgyebu_backend_renewal.expense.DailySpending;
import org.altair.petgyebu_backend_renewal.expense.ExpenseRepository;
import org.altair.petgyebu_backend_renewal.expense.MonthlySpending;
import org.altair.petgyebu_backend_renewal.expense.MonthlySpending.CategoryTotal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatsService {

    public record Stats(YearMonth month, long spentAmount, long expenseCount, BudgetUsage usage,
            List<CategoryStat> categories) {
    }

    public record CategoryStat(Category category, long spentAmount, long expenseCount, BigDecimal sharePercent) {
    }

    public record DailyTotals(YearMonth month, List<DailySpending.DayTotal> days) {
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

    // 지출 행을 한 번만 조회해 합계와 카테고리를 함께 계산한다(카테고리 합 = 월 합계).
    @Transactional(readOnly = true)
    public Stats stats(long userId, String monthText) {
        YearMonth month = Months.parsePastOrCurrent(monthText, clock);
        Months.Range range = Months.range(month);
        MonthlySpending spending = MonthlySpending.of(expenses.findAmounts(userId, range.from(), range.to()));
        long spent = spending.total();
        List<CategoryStat> categories = spending.categories().stream()
                .sorted(ORDER)
                .map(t -> new CategoryStat(t.category(), t.spentAmount(), t.expenseCount(), share(t.spentAmount(), spent)))
                .toList();
        BudgetUsage usage = budgets.find(userId, range.from()).map(b -> new BudgetUsage(b.amount(), spent)).orElse(null);
        return new Stats(month, spent, spending.count(), usage, categories);
    }

    // 지출이 있는 날만 날짜 오름차순으로 돌려준다. 달력 합 = 월 stats 합이다.
    @Transactional(readOnly = true)
    public DailyTotals dailyTotals(long userId, String monthText) {
        YearMonth month = Months.parsePastOrCurrent(monthText, clock);
        Months.Range range = Months.range(month);
        return new DailyTotals(month, DailySpending.of(expenses.findDayAmounts(userId, range.from(), range.to())));
    }

    // 카테고리가 있으면 spent > 0이다. 소수 첫째 자리 반올림(HALF_UP), 합계 100 보정은 하지 않는다.
    private static BigDecimal share(long categorySpent, long spent) {
        return BigDecimal.valueOf(categorySpent).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(spent), 1, RoundingMode.HALF_UP);
    }
}
