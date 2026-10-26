package org.altair.petgyebu_backend_renewal.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;

// 한 달의 예산 대비 지출. 홈과 월 통계가 같은 계산 규칙을 쓴다.
// 실수 오차를 피하려고 BigDecimal로 정확히 계산한다.
public record BudgetUsage(long budgetAmount, long spentAmount) {

    public enum PetState { STABLE, CAUTION, WARNING, BUDGET_REACHED }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public long balanceAmount() {
        return budgetAmount - spentAmount;
    }

    // 표시용. 소수 첫째 자리에서 버린다 (49.99 → 49.9).
    public BigDecimal usagePercent() {
        return percent().divide(BigDecimal.valueOf(budgetAmount), 1, RoundingMode.DOWN);
    }

    // 버림 전 실제 비율로 판정한다. 정확히 50·80·100%는 새 구간에 속한다.
    public PetState petState() {
        if (reaches(100)) {
            return PetState.BUDGET_REACHED;
        }
        if (reaches(80)) {
            return PetState.WARNING;
        }
        if (reaches(50)) {
            return PetState.CAUTION;
        }
        return PetState.STABLE;
    }

    private boolean reaches(int threshold) {
        return percent().compareTo(BigDecimal.valueOf(budgetAmount).multiply(BigDecimal.valueOf(threshold))) >= 0;
    }

    private BigDecimal percent() {
        return BigDecimal.valueOf(spentAmount).multiply(HUNDRED);
    }
}
