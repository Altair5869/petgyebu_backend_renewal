package org.altair.petgyebu_backend_renewal.insight;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.altair.petgyebu_backend_renewal.budget.MonthlyBudget;
import org.altair.petgyebu_backend_renewal.budget.MonthlyBudgetRepository;
import org.altair.petgyebu_backend_renewal.common.Months;
import org.altair.petgyebu_backend_renewal.expense.ExpenseAmount;
import org.altair.petgyebu_backend_renewal.expense.ExpenseDayAmount;
import org.altair.petgyebu_backend_renewal.expense.ExpenseRepository;
import org.altair.petgyebu_backend_renewal.expense.MonthlySpending;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InsightService {

    public record Insights(LocalDate today, WeeklyComparison weekly, BudgetForecast forecast) {
    }

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final MonthlyBudgetRepository budgets;
    private final ExpenseRepository expenses;
    private final Clock clock;

    public InsightService(MonthlyBudgetRepository budgets, ExpenseRepository expenses, Clock clock) {
        this.budgets = budgets;
        this.expenses = expenses;
        this.clock = clock;
    }

    // 지출 행을 한 번만 조회해 주간 비교와 현재 월 합계를 함께 계산한다(한 스냅숏).
    @Transactional(readOnly = true)
    public Insights insights(long userId) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), SEOUL);
        Months.Range month = Months.range(YearMonth.from(today));
        LocalDate lastWeekFrom = WeeklyComparison.lastWeek(today).from();
        LocalDate from = month.from().isBefore(lastWeekFrom) ? month.from() : lastWeekFrom;
        List<ExpenseDayAmount> rows = expenses.findDayAmounts(userId, from, today.plusDays(1));

        // 지난달에 걸친 주의 행은 주간 비교에만 쓰고 월 합계에서는 뺀다.
        List<ExpenseAmount> monthRows = rows.stream()
                .filter(r -> !r.expenseDate().isBefore(month.from()))
                .map(r -> new ExpenseAmount(r.category(), r.amount()))
                .toList();
        long spent = MonthlySpending.of(monthRows).total();
        Long budget = budgets.find(userId, month.from()).map(MonthlyBudget::amount).orElse(null);
        return new Insights(today, WeeklyComparison.of(today, rows), BudgetForecast.of(today, budget, spent));
    }
}
