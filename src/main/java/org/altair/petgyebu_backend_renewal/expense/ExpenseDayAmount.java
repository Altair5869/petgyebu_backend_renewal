package org.altair.petgyebu_backend_renewal.expense;

import java.time.LocalDate;

// 날짜별 비교에 필요한 지출 행의 일자·카테고리·금액만 담는다.
public record ExpenseDayAmount(LocalDate expenseDate, Category category, long amount) {
}
