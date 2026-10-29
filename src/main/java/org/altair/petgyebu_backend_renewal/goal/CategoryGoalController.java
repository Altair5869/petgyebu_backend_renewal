package org.altair.petgyebu_backend_renewal.goal;

import java.math.BigDecimal;
import java.util.List;
import org.altair.petgyebu_backend_renewal.auth.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/months/{month}/category-goals")
public class CategoryGoalController {

    record GoalResponse(String category, long goalAmount, long spentAmount, long remainingAmount,
            BigDecimal usagePercent) {
    }

    record MonthGoalsResponse(String month, Long budgetAmount, long goalTotalAmount, boolean goalsExceedBudget,
            List<GoalResponse> goals) {
    }

    record PutResponse(String month, String category, long amount) {
    }

    private final CategoryGoalService service;

    public CategoryGoalController(CategoryGoalService service) {
        this.service = service;
    }

    @GetMapping
    MonthGoalsResponse get(@AuthenticationPrincipal AuthUser authUser, @PathVariable String month) {
        CategoryGoalService.MonthGoals result = service.get(authUser.userId(), month);
        List<GoalResponse> items = result.progress().stream()
                .map(p -> new GoalResponse(p.category().name(), p.goalAmount(), p.spentAmount(),
                        p.remainingAmount(), p.usagePercent()))
                .toList();
        return new MonthGoalsResponse(result.month().toString(), result.budgetAmount(), result.goals().total(),
                result.goals().exceeds(result.budgetAmount()), items);
    }

    @PutMapping("/{category}")
    PutResponse put(@AuthenticationPrincipal AuthUser authUser, @PathVariable String month,
            @PathVariable String category, @RequestBody JsonNode body) {
        CategoryGoal goal = service.put(authUser.userId(), month, category, body);
        return new PutResponse(month, goal.category().name(), goal.amount());
    }

    @DeleteMapping("/{category}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal AuthUser authUser, @PathVariable String month,
            @PathVariable String category) {
        service.delete(authUser.userId(), month, category);
    }
}
