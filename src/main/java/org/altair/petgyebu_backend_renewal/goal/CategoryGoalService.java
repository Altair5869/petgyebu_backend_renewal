package org.altair.petgyebu_backend_renewal.goal;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import org.altair.petgyebu_backend_renewal.budget.MonthlyBudget;
import org.altair.petgyebu_backend_renewal.budget.MonthlyBudgetRepository;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.altair.petgyebu_backend_renewal.common.DbErrors;
import org.altair.petgyebu_backend_renewal.common.Months;
import org.altair.petgyebu_backend_renewal.expense.Category;
import org.altair.petgyebu_backend_renewal.expense.ExpenseRepository;
import org.altair.petgyebu_backend_renewal.expense.MonthlySpending;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class CategoryGoalService {

    // budgetAmount는 예산이 없으면 null이다.
    public record MonthGoals(YearMonth month, Long budgetAmount, CategoryGoals goals,
            List<CategoryGoals.Progress> progress) {
    }

    private final CategoryGoalRepository goals;
    private final MonthlyBudgetRepository budgets;
    private final ExpenseRepository expenses;
    private final Clock clock;

    public CategoryGoalService(CategoryGoalRepository goals, MonthlyBudgetRepository budgets,
            ExpenseRepository expenses, Clock clock) {
        this.goals = goals;
        this.budgets = budgets;
        this.expenses = expenses;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MonthGoals get(long userId, String monthText) {
        YearMonth month = Months.parsePastOrCurrent(monthText, clock);
        Months.Range range = Months.range(month);
        Long budgetAmount = budgets.find(userId, range.from()).map(MonthlyBudget::amount).orElse(null);
        CategoryGoals monthGoals = CategoryGoals.of(goals.findAll(userId, range.from()));
        MonthlySpending spending = MonthlySpending.of(expenses.findAmounts(userId, range.from(), range.to()));
        return new MonthGoals(month, budgetAmount, monthGoals, monthGoals.progress(spending));
    }

    @Transactional
    public CategoryGoal put(long userId, String monthText, String categoryText, JsonNode body) {
        YearMonth month = Months.parsePastOrCurrent(monthText, clock);
        Category category = Category.parse(categoryText);
        CategoryGoalRequest request = CategoryGoalRequest.from(body);
        MonthlyBudget budget = requireMonthBudget(userId, month);
        validateWithinBudget(userId, month, category, request.amount(), budget);
        try {
            return goals.upsert(userId, month.atDay(1), category.name(), request.amount(), OffsetDateTime.now(clock));
        } catch (DataIntegrityViolationException e) {
            // 인증 직후 동시 탈퇴로 사용자 행이 사라졌다면 /users/me와 같이 401로 응답한다.
            if (DbErrors.isForeignKeyViolation(e)) {
                throw ApiException.unauthorized();
            }
            throw e;
        }
    }

    // 없는 목표를 지워도 204이므로 예산은 확인하지 않는다.
    @Transactional
    public void delete(long userId, String monthText, String categoryText) {
        YearMonth month = Months.parsePastOrCurrent(monthText, clock);
        Category category = Category.parse(categoryText);
        goals.delete(userId, month.atDay(1), category.name());
    }

    // 예산 행을 잠가 같은 사용자·월의 목표 저장을 하나씩 처리한다.
    private MonthlyBudget requireMonthBudget(long userId, YearMonth month) {
        return budgets.findForUpdate(userId, month.atDay(1)).orElseThrow(ApiException::monthBudgetRequired);
    }

    // 합이 예산과 같은 것은 허용한다. 바꾸는 카테고리의 기존 금액은 합에서 뺀다.
    private void validateWithinBudget(long userId, YearMonth month, Category category, long amount,
            MonthlyBudget budget) {
        CategoryGoals current = CategoryGoals.of(goals.findAll(userId, month.atDay(1)));
        if (current.totalWith(category, amount) > budget.amount()) {
            throw ApiException.categoryGoalsExceedBudget();
        }
    }
}
