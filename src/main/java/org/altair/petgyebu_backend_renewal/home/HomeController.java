package org.altair.petgyebu_backend_renewal.home;

import java.math.BigDecimal;
import org.altair.petgyebu_backend_renewal.auth.AuthUser;
import org.altair.petgyebu_backend_renewal.budget.BudgetUsage;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {

    record HomeResponse(String month, String petType, BudgetUsage.PetState petState, long budgetAmount,
            long spentAmount, BigDecimal usagePercent, long budgetBalanceAmount) {
    }

    private final HomeService homeService;

    public HomeController(HomeService homeService) {
        this.homeService = homeService;
    }

    @GetMapping("/api/v1/home")
    HomeResponse home(@AuthenticationPrincipal AuthUser authUser) {
        HomeService.Home home = homeService.home(authUser.userId());
        BudgetUsage usage = home.usage();
        return new HomeResponse(home.month().toString(), home.petType(), usage.petState(), usage.budgetAmount(),
                usage.spentAmount(), usage.usagePercent(), usage.balanceAmount());
    }
}
