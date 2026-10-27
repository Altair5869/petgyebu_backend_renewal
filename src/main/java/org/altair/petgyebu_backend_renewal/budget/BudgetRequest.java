package org.altair.petgyebu_backend_renewal.budget;

import org.altair.petgyebu_backend_renewal.common.RequestBodies;
import tools.jackson.databind.JsonNode;

// 월 예산 설정 본문. 검증 순서는 본문 → amount다.
record BudgetRequest(long amount) {

    static BudgetRequest from(JsonNode body) {
        RequestBodies.requireObject(body);
        return new BudgetRequest(RequestBodies.amount(body.get("amount")));
    }
}
