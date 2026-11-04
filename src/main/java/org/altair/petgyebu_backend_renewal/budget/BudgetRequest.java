package org.altair.petgyebu_backend_renewal.budget;

import org.altair.petgyebu_backend_renewal.common.Amount;

// 월 예산 설정 본문.
record BudgetRequest(@Amount Long amount) {
}
