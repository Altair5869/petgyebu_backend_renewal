package org.altair.petgyebu_backend_renewal.expense;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.altair.petgyebu_backend_renewal.common.RequestBodies;
import tools.jackson.databind.JsonNode;

// 지출 부분 수정 본문. null은 "보내지 않음"이고, memo만 명시적 null(지우기)과 구분하려고 memoPresent를 둔다.
record ExpensePatch(Long amount, LocalDate expenseDate, Category category, boolean memoPresent, String memo) {

    // 본문 객체 검사는 호출자가 먼저 한다(존재 확인 404보다 앞). 보낸 필드만 amount → expenseDate → category → memo 순.
    static ExpensePatch from(JsonNode body, LocalDate today) {
        Long amount = body.has("amount") ? RequestBodies.amount(body.get("amount")) : null;
        LocalDate expenseDate = body.has("expenseDate")
                ? ExpenseFields.expenseDate(body.get("expenseDate"), today)
                : null;
        Category category = body.has("category") ? ExpenseFields.category(body.get("category")) : null;
        boolean memoPresent = body.has("memo");
        String memo = memoPresent ? ExpenseFields.memo(body.get("memo")) : null;
        return new ExpensePatch(amount, expenseDate, category, memoPresent, memo);
    }

    // 생략한 필드는 현재 값을 유지한다.
    Expense applyTo(Expense current, OffsetDateTime now) {
        return new Expense(current.id(), current.userId(),
                amount != null ? amount : current.amount(),
                expenseDate != null ? expenseDate : current.expenseDate(),
                category != null ? category : current.category(),
                memoPresent ? memo : current.memo(),
                current.createdAt(), now, current.idempotencyKey());
    }
}
