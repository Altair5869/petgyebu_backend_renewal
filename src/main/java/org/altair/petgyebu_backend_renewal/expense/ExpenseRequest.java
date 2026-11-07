package org.altair.petgyebu_backend_renewal.expense;

import java.time.LocalDate;
import org.altair.petgyebu_backend_renewal.common.Amount;

// 지출 생성 본문. 검증은 컨트롤러의 @Valid가 하고, 아래 접근자는 검증을 통과한 값을 정규화한다.
record ExpenseRequest(@Amount Long amount, @ExpenseDate String expenseDate, @CategoryName String category,
        @Memo String memo) {

    LocalDate date() {
        return LocalDate.parse(expenseDate);
    }

    // 생략·null은 OTHER.
    Category categoryOrDefault() {
        return category == null ? Category.OTHER : Category.parse(category);
    }

    // 생략·null·공백만 있는 메모는 null.
    String normalizedMemo() {
        return memo == null || memo.isBlank() ? null : memo;
    }
}
