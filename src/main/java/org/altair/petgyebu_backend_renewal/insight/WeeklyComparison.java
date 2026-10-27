package org.altair.petgyebu_backend_renewal.insight;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.altair.petgyebu_backend_renewal.expense.Category;
import org.altair.petgyebu_backend_renewal.expense.ExpenseDayAmount;

// 이번 주(월요일~오늘)와 지난주 같은 요일 구간의 카테고리별 지출을 비교한다. 두 구간 밖의 행은 무시한다.
public final class WeeklyComparison {

    // 양 끝을 포함하는 날짜 범위
    public record DateRange(LocalDate from, LocalDate to) {
        boolean contains(LocalDate date) {
            return !date.isBefore(from) && !date.isAfter(to);
        }
    }

    public record CategoryChange(Category category, long thisWeekAmount, long lastWeekAmount, long changeAmount) {
    }

    private final DateRange thisWeek;
    private final DateRange lastWeek;
    private final List<CategoryChange> categories;

    private WeeklyComparison(DateRange thisWeek, DateRange lastWeek, List<CategoryChange> categories) {
        this.thisWeek = thisWeek;
        this.lastWeek = lastWeek;
        this.categories = categories;
    }

    public static DateRange thisWeek(LocalDate today) {
        return new DateRange(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today);
    }

    public static DateRange lastWeek(LocalDate today) {
        DateRange thisWeek = thisWeek(today);
        return new DateRange(thisWeek.from().minusDays(7), thisWeek.to().minusDays(7));
    }

    public static WeeklyComparison of(LocalDate today, List<ExpenseDayAmount> rows) {
        DateRange thisWeek = thisWeek(today);
        DateRange lastWeek = lastWeek(today);
        // EnumMap은 Category 선언 순서로 순회한다.
        Map<Category, Long> thisWeekSums = new EnumMap<>(Category.class);
        Map<Category, Long> lastWeekSums = new EnumMap<>(Category.class);
        for (ExpenseDayAmount row : rows) {
            if (thisWeek.contains(row.expenseDate())) {
                thisWeekSums.merge(row.category(), row.amount(), Long::sum);
            } else if (lastWeek.contains(row.expenseDate())) {
                lastWeekSums.merge(row.category(), row.amount(), Long::sum);
            }
        }
        // 정렬이 안정적이므로 증감이 같으면 Category 선언 순서가 유지된다.
        List<CategoryChange> categories = Arrays.stream(Category.values())
                .filter(c -> thisWeekSums.containsKey(c) || lastWeekSums.containsKey(c))
                .map(c -> {
                    long now = thisWeekSums.getOrDefault(c, 0L);
                    long before = lastWeekSums.getOrDefault(c, 0L);
                    return new CategoryChange(c, now, before, now - before);
                })
                .sorted(Comparator.comparingLong(CategoryChange::changeAmount).reversed())
                .toList();
        return new WeeklyComparison(thisWeek, lastWeek, categories);
    }

    public DateRange thisWeek() {
        return thisWeek;
    }

    public DateRange lastWeek() {
        return lastWeek;
    }

    public List<CategoryChange> categories() {
        return categories;
    }
}
