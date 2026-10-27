package org.altair.petgyebu_backend_renewal.insight;

import java.time.LocalDate;
import java.util.List;
import org.altair.petgyebu_backend_renewal.auth.AuthUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InsightController {

    record InsightsResponse(LocalDate today, WeeklyResponse weekly, ForecastResponse forecast) {
    }

    record WeeklyResponse(WeeklyComparison.DateRange thisWeek, WeeklyComparison.DateRange lastWeek,
            List<WeeklyComparison.CategoryChange> categories) {
    }

    record ForecastResponse(BudgetForecast.Status status, Long dailyAverageAmount, LocalDate exhaustionDate) {
    }

    private final InsightService insightService;

    public InsightController(InsightService insightService) {
        this.insightService = insightService;
    }

    @GetMapping("/api/v1/insights")
    InsightsResponse insights(@AuthenticationPrincipal AuthUser authUser) {
        InsightService.Insights insights = insightService.insights(authUser.userId());
        WeeklyComparison weekly = insights.weekly();
        BudgetForecast forecast = insights.forecast();
        return new InsightsResponse(insights.today(),
                new WeeklyResponse(weekly.thisWeek(), weekly.lastWeek(), weekly.categories()),
                new ForecastResponse(forecast.status(), forecast.dailyAverageAmount(), forecast.exhaustionDate()));
    }
}
