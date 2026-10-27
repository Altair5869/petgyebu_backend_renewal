package org.altair.petgyebu_backend_renewal.expense;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// 조회한 지출 행으로 월 합계·건수·카테고리별 합계를 계산한다. 지출이 없으면 0원·0건·빈 목록이다.
public final class MonthlySpending {

    public record CategoryTotal(Category category, long spentAmount, long expenseCount) {
    }

    private final long total;
    private final long count;
    private final List<CategoryTotal> categories;

    private MonthlySpending(long total, long count, List<CategoryTotal> categories) {
        this.total = total;
        this.count = count;
        this.categories = categories;
    }

    public static MonthlySpending of(List<ExpenseAmount> rows) {
        long total = 0;
        // EnumMap은 Category 선언 순서로 순회한다.
        Map<Category, long[]> byCategory = new EnumMap<>(Category.class);
        for (ExpenseAmount row : rows) {
            total += row.amount();
            long[] sums = byCategory.computeIfAbsent(row.category(), c -> new long[2]);
            sums[0] += row.amount();
            sums[1]++;
        }
        List<CategoryTotal> categories = new ArrayList<>();
        byCategory.forEach((category, sums) -> categories.add(new CategoryTotal(category, sums[0], sums[1])));
        return new MonthlySpending(total, rows.size(), List.copyOf(categories));
    }

    public long total() {
        return total;
    }

    public long count() {
        return count;
    }

    public List<CategoryTotal> categories() {
        return categories;
    }
}
