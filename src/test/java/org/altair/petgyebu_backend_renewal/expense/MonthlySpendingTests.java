package org.altair.petgyebu_backend_renewal.expense;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonthlySpendingTests {

    @Test
    void emptyMonthIsZero() {
        MonthlySpending s = MonthlySpending.of(List.of());
        assertThat(s.total()).isZero();
        assertThat(s.count()).isZero();
        assertThat(s.categories()).isEmpty();
    }

    @Test
    void sumsAndCountsPerCategoryInDeclarationOrder() {
        MonthlySpending s = MonthlySpending.of(List.of(new ExpenseAmount(Category.OTHER, 500),
                new ExpenseAmount(Category.FOOD, 1000), new ExpenseAmount(Category.FOOD, 2000)));
        assertThat(s.total()).isEqualTo(3500);
        assertThat(s.count()).isEqualTo(3);
        assertThat(s.categories()).containsExactly(new MonthlySpending.CategoryTotal(Category.FOOD, 3000, 2),
                new MonthlySpending.CategoryTotal(Category.OTHER, 500, 1));
    }

    @Test
    void largeTotalsDoNotOverflow() {
        List<ExpenseAmount> rows = Collections.nCopies(26, new ExpenseAmount(Category.FOOD, 100_000_000));
        assertThat(MonthlySpending.of(rows).total()).isEqualTo(2_600_000_000L);
    }
}
