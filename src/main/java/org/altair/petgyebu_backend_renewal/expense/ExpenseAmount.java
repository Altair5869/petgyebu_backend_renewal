package org.altair.petgyebu_backend_renewal.expense;

// 월 집계에 쓰는 지출 행의 카테고리·금액.
public record ExpenseAmount(Category category, long amount) {
}
