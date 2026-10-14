package org.altair.petgyebu_backend_renewal.budget;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.Optional;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.altair.petgyebu_backend_renewal.common.Months;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class BudgetService {

    static final long MAX_AMOUNT = 100_000_000;

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

    // 현재 월만 설정·변경한다. 새 달 예산을 자동으로 만들지 않는다.
    public MonthlyBudget put(long userId, String monthText, JsonNode body) {
        YearMonth month = Months.parsePastOrCurrent(monthText, clock);
        if (!month.equals(Months.current(clock))) {
            throw ApiException.invalidRequest("month", "현재 월만 설정할 수 있습니다.");
        }
        if (body == null || !body.isObject()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 본문을 읽을 수 없습니다.", null);
        }
        JsonNode node = body.get("amount");
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 1
                || node.longValue() > MAX_AMOUNT) {
            throw ApiException.invalidRequest("amount", "1 이상 " + MAX_AMOUNT + " 이하의 정수여야 합니다.");
        }
        return budgets.upsert(userId, month.atDay(1), node.longValue(), OffsetDateTime.now(clock));
    }
}
