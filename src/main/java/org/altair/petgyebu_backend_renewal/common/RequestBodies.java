package org.altair.petgyebu_backend_renewal.common;

import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

// 생략과 명시적 null을 구분해야 해서 본문을 JsonNode로 받는다. 그 공통 검증을 모은다.
public final class RequestBodies {

    public static final long MAX_AMOUNT = 100_000_000;

    private RequestBodies() {
    }

    public static void requireObject(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 본문을 읽을 수 없습니다.", null);
        }
    }

    public static long amount(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 1
                || node.longValue() > MAX_AMOUNT) {
            throw ApiException.invalidRequest("amount", "1 이상 " + MAX_AMOUNT + " 이하의 정수여야 합니다.");
        }
        return node.longValue();
    }
}
