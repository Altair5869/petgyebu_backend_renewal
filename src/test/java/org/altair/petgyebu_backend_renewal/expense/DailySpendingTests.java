package org.altair.petgyebu_backend_renewal.expense;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import org.altair.petgyebu_backend_renewal.expense.DailySpending.DayTotal;
import org.junit.jupiter.api.Test;

class DailySpendingTests {

    @Test
    void emptyRowsGiveNoDays() {
        assertThat(DailySpending.of(List.of())).isEmpty();
    }

    @Test
    void sumsSameDayAcrossCategories() {
        List<DayTotal> days = DailySpending.of(List.of(
                new ExpenseDayAmount(LocalDate.of(2026, 10, 28), Category.FOOD, 11000),
                new ExpenseDayAmount(LocalDate.of(2026, 10, 28), Category.CAFE, 12000)));
        assertThat(days).containsExactly(new DayTotal(LocalDate.of(2026, 10, 28), 23000, 2));
    }

    @Test
    void ordersDaysAscendingRegardlessOfRowOrder() {
        List<DayTotal> days = DailySpending.of(List.of(
                new ExpenseDayAmount(LocalDate.of(2026, 10, 30), Category.FOOD, 1000),
                new ExpenseDayAmount(LocalDate.of(2026, 10, 1), Category.FOOD, 1000),
                new ExpenseDayAmount(LocalDate.of(2026, 10, 15), Category.FOOD, 1000)));
        assertThat(days).extracting(DayTotal::date).containsExactly(LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 30));
    }

    @Test
    void largeDayTotalDoesNotOverflow() {
        List<ExpenseDayAmount> rows = Collections.nCopies(26,
                new ExpenseDayAmount(LocalDate.of(2026, 10, 1), Category.FOOD, 100_000_000));
        assertThat(DailySpending.of(rows).get(0).spentAmount()).isEqualTo(2_600_000_000L);
    }
}
