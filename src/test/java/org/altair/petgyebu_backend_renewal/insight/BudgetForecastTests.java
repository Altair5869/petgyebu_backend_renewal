package org.altair.petgyebu_backend_renewal.insight;

import static org.altair.petgyebu_backend_renewal.insight.BudgetForecast.Status.BUDGET_REACHED;
import static org.altair.petgyebu_backend_renewal.insight.BudgetForecast.Status.NO_BUDGET;
import static org.altair.petgyebu_backend_renewal.insight.BudgetForecast.Status.TOO_EARLY;
import static org.altair.petgyebu_backend_renewal.insight.BudgetForecast.Status.WILL_EXHAUST;
import static org.altair.petgyebu_backend_renewal.insight.BudgetForecast.Status.WITHIN_BUDGET;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class BudgetForecastTests {

    @Test
    void noBudgetEvenOnFirstDay() {
        expect(of("2026-10-01", null, 0), NO_BUDGET, null, null);
    }

    @Test
    void budgetReachedIsShownEvenEarlyInMonth() {
        expect(of("2026-10-02", 100000L, 100000), BUDGET_REACHED, null, null);
    }

    @Test
    void exactlySpentEqualsBudgetIsReached() {
        expect(of("2026-10-13", 300000L, 300000), BUDGET_REACHED, null, null);
    }

    @Test
    void dayThreeIsTooEarlyDayFourIsForecast() {
        expect(of("2026-10-03", 100000L, 30000), TOO_EARLY, null, null);
        expect(of("2026-10-04", 100000L, 40000), WILL_EXHAUST, 10000L, "2026-10-10");
    }

    @Test
    void noSpendingIsWithinBudget() {
        expect(of("2026-10-13", 300000L, 0), WITHIN_BUDGET, 0L, null);
    }

    @Test
    void paceReachingBudgetInsideMonthGivesDate() {
        expect(of("2026-10-13", 300000L, 130000), WILL_EXHAUST, 10000L, "2026-10-30");
    }

    @Test
    void paceBeyondMonthEndIsWithinBudget() {
        expect(of("2026-10-13", 300000L, 120000), WITHIN_BUDGET, 9230L, null);
    }

    @Test
    void reachingBudgetExactlyOnLastDayIsExhaust() {
        expect(of("2026-10-10", 310000L, 100000), WILL_EXHAUST, 10000L, "2026-10-31");
    }

    @Test
    void largeAmountsDoNotOverflow() {
        expect(of("2026-10-30", 100_000_000L, 99_999_999), WILL_EXHAUST, 3_333_333L, "2026-10-31");
    }

    @Test
    void leapFebruaryLastDayIsExhaust() {
        expect(of("2028-02-10", 290000L, 100000), WILL_EXHAUST, 10000L, "2028-02-29");
    }

    @Test
    void samePaceRunsPastTwentyEightDayFebruary() {
        expect(of("2027-02-10", 290000L, 100000), WITHIN_BUDGET, 10000L, null);
    }

    private static BudgetForecast of(String date, Long budget, long spent) {
        return BudgetForecast.of(LocalDate.parse(date), budget, spent);
    }

    private static void expect(BudgetForecast f, BudgetForecast.Status status, Long avg, String date) {
        assertThat(f.status()).isEqualTo(status);
        assertThat(f.dailyAverageAmount()).isEqualTo(avg);
        assertThat(f.exhaustionDate()).isEqualTo(date == null ? null : LocalDate.parse(date));
    }
}
