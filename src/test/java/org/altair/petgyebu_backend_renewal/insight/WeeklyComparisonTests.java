package org.altair.petgyebu_backend_renewal.insight;

import static org.altair.petgyebu_backend_renewal.expense.Category.CAFE;
import static org.altair.petgyebu_backend_renewal.expense.Category.FOOD;
import static org.altair.petgyebu_backend_renewal.expense.Category.TRANSPORT;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.altair.petgyebu_backend_renewal.expense.Category;
import org.altair.petgyebu_backend_renewal.expense.ExpenseDayAmount;
import org.altair.petgyebu_backend_renewal.insight.WeeklyComparison.CategoryChange;
import org.altair.petgyebu_backend_renewal.insight.WeeklyComparison.DateRange;
import org.junit.jupiter.api.Test;

class WeeklyComparisonTests {

    private static ExpenseDayAmount row(String date, Category category, long amount) {
        return new ExpenseDayAmount(LocalDate.parse(date), category, amount);
    }

    @Test
    void tuesdayComparesMondayToTuesdayOnly() {
        LocalDate today = LocalDate.of(2026, 10, 13);
        WeeklyComparison w = WeeklyComparison.of(today, List.of(
                row("2026-10-12", CAFE, 15000), row("2026-10-13", CAFE, 6000), row("2026-10-13", FOOD, 5000),
                row("2026-10-05", CAFE, 8000), row("2026-10-06", TRANSPORT, 3000),
                row("2026-10-07", FOOD, 99999), row("2026-10-04", FOOD, 77777)));
        assertThat(w.thisWeek()).isEqualTo(new DateRange(LocalDate.of(2026, 10, 12), today));
        assertThat(w.lastWeek()).isEqualTo(new DateRange(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6)));
        assertThat(w.categories()).containsExactly(
                new CategoryChange(CAFE, 21000, 8000, 13000),
                new CategoryChange(FOOD, 5000, 0, 5000),
                new CategoryChange(TRANSPORT, 0, 3000, -3000));
    }

    @Test
    void mondayComparesSingleDays() {
        LocalDate mon = LocalDate.of(2026, 10, 12);
        WeeklyComparison w = WeeklyComparison.of(mon, List.of());
        assertThat(w.thisWeek()).isEqualTo(new DateRange(mon, mon));
        assertThat(w.lastWeek()).isEqualTo(new DateRange(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5)));
        assertThat(w.categories()).isEmpty();
    }

    @Test
    void sundayComparesWholeWeeks() {
        WeeklyComparison w = WeeklyComparison.of(LocalDate.of(2026, 10, 18), List.of());
        assertThat(w.thisWeek()).isEqualTo(new DateRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18)));
        assertThat(w.lastWeek()).isEqualTo(new DateRange(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11)));
    }

    @Test
    void weekSpanningMonthsKeepsCalendarWeek() {
        WeeklyComparison w = WeeklyComparison.of(LocalDate.of(2026, 11, 1),
                List.of(row("2026-10-26", CAFE, 1000), row("2026-11-01", CAFE, 2000), row("2026-10-19", CAFE, 500)));
        assertThat(w.thisWeek()).isEqualTo(new DateRange(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1)));
        assertThat(w.categories()).containsExactly(new CategoryChange(CAFE, 3000, 500, 2500));
    }

    @Test
    void equalChangesFollowCategoryOrder() {
        WeeklyComparison w = WeeklyComparison.of(LocalDate.of(2026, 10, 13),
                List.of(row("2026-10-13", CAFE, 1000), row("2026-10-13", FOOD, 1000)));
        assertThat(w.categories()).extracting(CategoryChange::category).containsExactly(FOOD, CAFE);
    }

    @Test
    void zeroChangeCategoryStillAppears() {
        WeeklyComparison w = WeeklyComparison.of(LocalDate.of(2026, 10, 13),
                List.of(row("2026-10-12", CAFE, 1000), row("2026-10-05", CAFE, 1000)));
        assertThat(w.categories()).containsExactly(new CategoryChange(CAFE, 1000, 1000, 0));
    }

    @Test
    void largeWeeklySumsDoNotOverflow() {
        List<ExpenseDayAmount> rows = java.util.stream.IntStream.range(0, 26)
                .mapToObj(i -> row("2026-10-13", FOOD, 100_000_000L)).toList();
        WeeklyComparison w = WeeklyComparison.of(LocalDate.of(2026, 10, 13), rows);
        assertThat(w.categories()).containsExactly(new CategoryChange(FOOD, 2_600_000_000L, 0, 2_600_000_000L));
    }
}
