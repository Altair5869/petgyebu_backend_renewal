package org.altair.petgyebu_backend_renewal.expense;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

// 사용자 집합에 속하지 않는 독립 집합. 소유권은 user_id로 확인한다.
@Table("expenses")
public record Expense(
        @Id Long id,
        long userId,
        long amount,
        LocalDate expenseDate,
        Category category,
        String memo,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        UUID idempotencyKey) {
}
