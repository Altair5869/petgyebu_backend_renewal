package org.altair.petgyebu_backend_renewal.expense;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.altair.petgyebu_backend_renewal.common.Amount;
import org.openapitools.jackson.nullable.JsonNullable;

// 지출 부분 수정 본문. 생략(undefined)은 검증하지 않고 현재 값을 유지한다. 명시적 null은 memo만 "지우기"로 허용한다.
record ExpensePatch(JsonNullable<@Amount Long> amount, JsonNullable<@ExpenseDate String> expenseDate,
        JsonNullable<@NotNull(message = "허용되지 않는 카테고리입니다.") @CategoryName String> category,
        JsonNullable<@Memo String> memo) {

    // 검증을 통과한 값만 온다. 보낸 필드만 바꾸고 memo는 정규화한다(공백만 → null).
    Expense applyTo(Expense current, OffsetDateTime now) {
        return new Expense(current.id(), current.userId(),
                amount.isPresent() ? amount.get() : current.amount(),
                expenseDate.isPresent() ? LocalDate.parse(expenseDate.get()) : current.expenseDate(),
                category.isPresent() ? Category.parse(category.get()) : current.category(),
                memo.isPresent() ? normalize(memo.get()) : current.memo(),
                current.createdAt(), now, current.idempotencyKey());
    }

    private static String normalize(String memo) {
        return memo == null || memo.isBlank() ? null : memo;
    }
}
