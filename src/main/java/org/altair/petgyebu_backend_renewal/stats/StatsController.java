package org.altair.petgyebu_backend_renewal.stats;

import java.math.BigDecimal;
import java.util.List;
import org.altair.petgyebu_backend_renewal.auth.AuthUser;
import org.altair.petgyebu_backend_renewal.budget.BudgetUsage;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatsController {

    // 펫 필드는 없다. 그 달 예산이 없으면 budgetAmount·usagePercent·budgetBalanceAmount만 null이다.
    // usagePercent(버림)·sharePercent(반올림)는 소수 한 자리 숫자다.
    record StatsResponse(String month, Long budgetAmount, long spentAmount, long expenseCount,
            BigDecimal usagePercent, Long budgetBalanceAmount, List<StatsService.CategoryStat> categories) {
    }

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/api/v1/months/{month}/stats")
    StatsResponse stats(@AuthenticationPrincipal AuthUser authUser, @PathVariable String month) {
        StatsService.Stats stats = statsService.stats(authUser.userId(), month);
        BudgetUsage usage = stats.usage();
        return new StatsResponse(stats.month().toString(),
                usage == null ? null : usage.budgetAmount(),
                stats.spentAmount(), stats.expenseCount(),
                usage == null ? null : usage.usagePercent(),
                usage == null ? null : usage.balanceAmount(),
                stats.categories());
    }
}
