package org.altair.petgyebu_backend_renewal.insight;

import java.time.LocalDate;

// 지금까지의 하루 평균 지출로 이번 달 예산이 언제 소진되는지 예측한다.
public final class BudgetForecast {

    public enum Status {
        WILL_EXHAUST, WITHIN_BUDGET, TOO_EARLY, BUDGET_REACHED, NO_BUDGET
    }

    // 1~3일차는 지출 한 번에 예측이 과해지므로 숨긴다
    private static final int TOO_EARLY_LAST_DAY = 3;

    private final Status status;
    private final Long dailyAverageAmount;
    private final LocalDate exhaustionDate;

    private BudgetForecast(Status status, Long dailyAverageAmount, LocalDate exhaustionDate) {
        this.status = status;
        this.dailyAverageAmount = dailyAverageAmount;
        this.exhaustionDate = exhaustionDate;
    }

    // budgetAmount가 null이면 예산 없음. spentAmount는 이번 달 1일~today 지출 합계
    public static BudgetForecast of(LocalDate today, Long budgetAmount, long spentAmount) {
        if (budgetAmount == null) {
            return new BudgetForecast(Status.NO_BUDGET, null, null);
        }
        if (spentAmount >= budgetAmount) {
            return new BudgetForecast(Status.BUDGET_REACHED, null, null);
        }
        int day = today.getDayOfMonth();
        if (day <= TOO_EARLY_LAST_DAY) {
            return new BudgetForecast(Status.TOO_EARLY, null, null);
        }
        if (spentAmount == 0) {
            return new BudgetForecast(Status.WITHIN_BUDGET, 0L, null);
        }
        long average = spentAmount / day;
        // 하루 평균으로 누적해 처음 예산 이상이 되는 날: 정수 올림 나눗셈
        long exhaustionDay = (budgetAmount * day + spentAmount - 1) / spentAmount;
        if (exhaustionDay <= today.lengthOfMonth()) {
            return new BudgetForecast(Status.WILL_EXHAUST, average, today.withDayOfMonth((int) exhaustionDay));
        }
        return new BudgetForecast(Status.WITHIN_BUDGET, average, null);
    }

    public Status status() {
        return status;
    }

    public Long dailyAverageAmount() {
        return dailyAverageAmount;
    }

    public LocalDate exhaustionDate() {
        return exhaustionDate;
    }
}
