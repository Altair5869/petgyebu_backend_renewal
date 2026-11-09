package org.altair.petgyebu_backend_renewal.expense;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import org.altair.petgyebu_backend_renewal.common.Amount;
import org.openapitools.jackson.nullable.JsonNullable;

// 지출 부분 수정 본문. 생략(undefined)은 검증하지 않고 현재 값을 유지한다. 명시적 null은 memo만 "지우기"로 허용한다.
record ExpensePatch(JsonNullable<@Amount Long> amount, JsonNullable<@ExpenseDate String> expenseDate,
        JsonNullable<@NotNull(message = "허용되지 않는 카테고리입니다.") @CategoryName String> category,
        JsonNullable<@Memo String> memo) {

    // 검증을 통과한 값만 온다. 보낸 필드의 새 값이며, 보내지 않았으면 null이다(보냈는지는 isPresent로 구분).
    LocalDate newDate() {
        return expenseDate.isPresent() ? LocalDate.parse(expenseDate.get()) : null;
    }

    String newCategory() {
        return category.isPresent() ? Category.parse(category.get()).name() : null;
    }

    // memo는 정규화한다(공백만 → null). 보낸 null도 "지우기"라 null이다.
    String newMemo() {
        return memo.isPresent() ? normalize(memo.get()) : null;
    }

    private static String normalize(String memo) {
        return memo == null || memo.isBlank() ? null : memo;
    }
}
