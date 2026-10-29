package org.altair.petgyebu_backend_renewal.goal;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.altair.petgyebu_backend_renewal.expense.Category;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

// 사용자 집합에 속하지 않는 독립 집합. goalMonth는 해당 월 1일이다.
@Table("category_goals")
public record CategoryGoal(
        @Id Long id,
        long userId,
        LocalDate goalMonth,
        Category category,
        long amount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
