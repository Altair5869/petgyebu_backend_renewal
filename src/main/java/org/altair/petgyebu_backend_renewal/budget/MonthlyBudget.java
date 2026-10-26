package org.altair.petgyebu_backend_renewal.budget;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

// 사용자 집합에 속하지 않는 독립 집합. budgetMonth는 해당 월 1일이다.
@Table("monthly_budgets")
public record MonthlyBudget(
        @Id Long id,
        long userId,
        LocalDate budgetMonth,
        long amount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
