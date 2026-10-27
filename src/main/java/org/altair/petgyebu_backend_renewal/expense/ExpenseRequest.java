package org.altair.petgyebu_backend_renewal.expense;

import java.time.LocalDate;
import org.altair.petgyebu_backend_renewal.common.RequestBodies;
import tools.jackson.databind.JsonNode;

// 지출 생성 본문. 검증 순서(본문 → amount → expenseDate → category → memo)가 첫 오류 순서다.
record ExpenseRequest(long amount, LocalDate expenseDate, Category category, String memo) {

    static ExpenseRequest from(JsonNode body, LocalDate today) {
        RequestBodies.requireObject(body);
        long amount = RequestBodies.amount(body.get("amount"));
        LocalDate expenseDate = ExpenseFields.expenseDate(body.get("expenseDate"), today);
        JsonNode categoryNode = body.get("category");
        Category category = categoryNode == null || categoryNode.isNull()
                ? Category.OTHER
                : ExpenseFields.category(categoryNode);
        String memo = ExpenseFields.memo(body.get("memo"));
        return new ExpenseRequest(amount, expenseDate, category, memo);
    }
}
