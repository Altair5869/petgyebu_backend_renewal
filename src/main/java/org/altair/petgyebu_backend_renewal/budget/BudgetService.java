package org.altair.petgyebu_backend_renewal.budget;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.Optional;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.altair.petgyebu_backend_renewal.common.DbErrors;
import org.altair.petgyebu_backend_renewal.common.Months;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class BudgetService {

    private final MonthlyBudgetRepository budgets;
    private final Clock clock;

    public BudgetService(MonthlyBudgetRepository budgets, Clock clock) {
        this.budgets = budgets;
        this.clock = clock;
    }

    public Optional<MonthlyBudget> get(long userId, String monthText) {
        YearMonth month = Months.parsePastOrCurrent(monthText, clock);
        return budgets.find(userId, month.atDay(1));
    }

    // 2000-01부터 현재 월까지 설정·변경한다(과거 월 정정 포함). 새 달 예산을 자동으로 만들지 않는다.
    public MonthlyBudget put(long userId, String monthText, JsonNode body) {
        YearMonth month = Months.parsePastOrCurrent(monthText, clock);
        BudgetRequest request = BudgetRequest.from(body);
        try {
            return budgets.upsert(userId, month.atDay(1), request.amount(), OffsetDateTime.now(clock));
        } catch (DataIntegrityViolationException e) {
            // 인증 직후 동시 탈퇴로 사용자 행이 사라졌다면 /users/me와 같이 401로 응답한다.
            if (DbErrors.isForeignKeyViolation(e)) {
                throw ApiException.unauthorized();
            }
            throw e;
        }
    }
}
