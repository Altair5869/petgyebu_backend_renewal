package org.altair.petgyebu_backend_renewal.budget;

import org.altair.petgyebu_backend_renewal.auth.AuthUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/months/{month}/budget")
public class BudgetController {

    // month는 YYYY-MM. 미설정이면 amount: null이다.
    record BudgetResponse(String month, Long amount) {
    }

    private final BudgetService budgetService;

    public BudgetController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    @GetMapping
    BudgetResponse get(@AuthenticationPrincipal AuthUser authUser, @PathVariable String month) {
        Long amount = budgetService.get(authUser.userId(), month).map(MonthlyBudget::amount).orElse(null);
        return new BudgetResponse(month, amount);
    }

    @PutMapping
    BudgetResponse put(@AuthenticationPrincipal AuthUser authUser, @PathVariable String month,
            @RequestBody JsonNode body) {
        return new BudgetResponse(month, budgetService.put(authUser.userId(), month, body).amount());
    }
}
