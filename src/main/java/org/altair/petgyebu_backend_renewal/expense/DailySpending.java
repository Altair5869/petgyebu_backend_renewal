package org.altair.petgyebu_backend_renewal.expense;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// 조회한 지출 행으로 날짜별 합계·건수를 계산한다. 지출이 있는 날만 날짜 오름차순이다.
public final class DailySpending {

    public record DayTotal(LocalDate date, long spentAmount, long expenseCount) {
    }

    private DailySpending() {
    }

    public static List<DayTotal> of(List<ExpenseDayAmount> rows) {
        Map<LocalDate, long[]> byDate = new TreeMap<>();
        for (ExpenseDayAmount row : rows) {
            long[] sums = byDate.computeIfAbsent(row.expenseDate(), d -> new long[2]);
            sums[0] += row.amount();
            sums[1]++;
        }
        List<DayTotal> days = new ArrayList<>();
        byDate.forEach((date, sums) -> days.add(new DayTotal(date, sums[0], sums[1])));
        return List.copyOf(days);
    }
}
