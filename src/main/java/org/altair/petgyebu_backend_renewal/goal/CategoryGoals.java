package org.altair.petgyebu_backend_renewal.goal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.altair.petgyebu_backend_renewal.budget.BudgetUsage;
import org.altair.petgyebu_backend_renewal.expense.Category;
import org.altair.petgyebu_backend_renewal.expense.MonthlySpending;

// 한 달의 카테고리 목표 묶음. 목표 합계와 카테고리별 진행률을 계산한다.
public final class CategoryGoals {

    public record Progress(Category category, long goalAmount, long spentAmount, long remainingAmount,
            BigDecimal usagePercent) {
    }

    // EnumMap은 Category 선언 순서로 순회한다.
    private final Map<Category, Long> amounts;

    private CategoryGoals(Map<Category, Long> amounts) {
        this.amounts = amounts;
    }

    public static CategoryGoals of(List<CategoryGoal> goals) {
        Map<Category, Long> amounts = new EnumMap<>(Category.class);
        for (CategoryGoal goal : goals) {
            amounts.put(goal.category(), goal.amount());
        }
        return new CategoryGoals(amounts);
    }

    public long total() {
        return amounts.values().stream().mapToLong(Long::longValue).sum();
    }

    // 그 카테고리를 amount로 바꿨을 때의 합. 기존 값은 빼고 계산한다.
    public long totalWith(Category category, long amount) {
        return total() - amounts.getOrDefault(category, 0L) + amount;
    }

    public boolean exceeds(Long budgetAmount) {
        return budgetAmount != null && total() > budgetAmount;
    }

    public List<Progress> progress(MonthlySpending spending) {
        Map<Category, Long> spent = new EnumMap<>(Category.class);
        for (MonthlySpending.CategoryTotal total : spending.categories()) {
            spent.put(total.category(), total.spentAmount());
        }
        List<Progress> result = new ArrayList<>();
        amounts.forEach((category, goalAmount) -> {
            long spentAmount = spent.getOrDefault(category, 0L);
            result.add(new Progress(category, goalAmount, spentAmount, goalAmount - spentAmount,
                    new BudgetUsage(goalAmount, spentAmount).usagePercent()));
        });
        return List.copyOf(result);
    }
}
