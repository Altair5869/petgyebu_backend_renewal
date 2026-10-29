package org.altair.petgyebu_backend_renewal.goal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.altair.petgyebu_backend_renewal.expense.Category;
import org.altair.petgyebu_backend_renewal.expense.ExpenseAmount;
import org.altair.petgyebu_backend_renewal.expense.MonthlySpending;
import org.junit.jupiter.api.Test;

class CategoryGoalsTests {

    private static CategoryGoal goal(Category category, long amount) {
        return new CategoryGoal(null, 1L, LocalDate.of(2026, 10, 1), category, amount, null, null);
    }

    private static MonthlySpending spending(Object... categoryAmountPairs) {
        List<ExpenseAmount> rows = new ArrayList<>();
        for (int i = 0; i < categoryAmountPairs.length; i += 2) {
            rows.add(new ExpenseAmount((Category) categoryAmountPairs[i], (long) categoryAmountPairs[i + 1]));
        }
        return MonthlySpending.of(rows);
    }

    @Test
    void progressListsOnlyGoalCategoriesInDeclarationOrder() {
        CategoryGoals goals = CategoryGoals.of(List.of(goal(Category.OTHER, 1000), goal(Category.FOOD, 2000)));

        List<CategoryGoals.Progress> progress = goals.progress(spending(Category.CAFE, 500L));

        assertThat(progress).containsExactly(
                new CategoryGoals.Progress(Category.FOOD, 2000, 0, 2000, new BigDecimal("0.0")),
                new CategoryGoals.Progress(Category.OTHER, 1000, 0, 1000, new BigDecimal("0.0")));
    }

    @Test
    void overspentGoalHasNegativeRemainingAndOverHundredPercent() {
        CategoryGoals goals = CategoryGoals.of(List.of(goal(Category.CAFE, 10000)));

        CategoryGoals.Progress p = goals.progress(spending(Category.CAFE, 12000L, Category.CAFE, 500L)).get(0);

        assertThat(p.spentAmount()).isEqualTo(12500);
        assertThat(p.remainingAmount()).isEqualTo(-2500);
        assertThat(p.usagePercent()).isEqualTo(new BigDecimal("125.0"));
    }

    @Test
    void usagePercentIsTruncated() {
        CategoryGoals goals = CategoryGoals.of(List.of(goal(Category.CAFE, 10000)));

        assertThat(goals.progress(spending(Category.CAFE, 4999L)).get(0).usagePercent())
                .isEqualTo(new BigDecimal("49.9"));
    }

    @Test
    void totalAndTotalWith() {
        CategoryGoals goals = CategoryGoals.of(List.of(goal(Category.FOOD, 3000), goal(Category.CAFE, 2000)));

        assertThat(goals.total()).isEqualTo(5000);
        assertThat(goals.totalWith(Category.CAFE, 500)).isEqualTo(3500);
        assertThat(goals.totalWith(Category.HEALTH, 1000)).isEqualTo(6000);
    }

    @Test
    void totalWithReplacesSameCategory() {
        CategoryGoals goals = CategoryGoals.of(List.of(goal(Category.CAFE, 60)));

        assertThat(goals.totalWith(Category.CAFE, 100)).isEqualTo(100);
    }

    @Test
    void exceedsOnlyWhenBudgetPresentAndBelowTotal() {
        CategoryGoals goals = CategoryGoals.of(List.of(goal(Category.FOOD, 3000), goal(Category.CAFE, 2000)));

        assertThat(goals.exceeds(5000L)).isFalse();
        assertThat(goals.exceeds(4999L)).isTrue();
        assertThat(goals.exceeds(null)).isFalse();

        CategoryGoals none = CategoryGoals.of(List.of());
        assertThat(none.total()).isZero();
        assertThat(none.exceeds(1L)).isFalse();
    }
}
