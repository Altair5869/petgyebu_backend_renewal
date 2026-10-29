package org.altair.petgyebu_backend_renewal.goal;

import org.altair.petgyebu_backend_renewal.common.RequestBodies;
import tools.jackson.databind.JsonNode;

// 카테고리 목표 설정 본문. 검증 순서는 본문 → amount다.
record CategoryGoalRequest(long amount) {

    static CategoryGoalRequest from(JsonNode body) {
        RequestBodies.requireObject(body);
        return new CategoryGoalRequest(RequestBodies.amount(body.get("amount")));
    }
}
