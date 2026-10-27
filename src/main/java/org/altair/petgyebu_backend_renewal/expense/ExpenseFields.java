package org.altair.petgyebu_backend_renewal.expense;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import tools.jackson.databind.JsonNode;

// 지출 요청 필드별 파서. 오류 메시지는 API 계약이다.
final class ExpenseFields {

    static final int MEMO_MAX_CODE_POINTS = 100;
    static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);

    private static final Pattern UUID_FORMAT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private ExpenseFields() {
    }

    static LocalDate expenseDate(JsonNode node, LocalDate today) {
        if (node == null || !node.isString() || !node.stringValue().matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw ApiException.invalidRequest("expenseDate", "YYYY-MM-DD 형식이어야 합니다.");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(node.stringValue());
        } catch (DateTimeParseException e) {
            throw ApiException.invalidRequest("expenseDate", "YYYY-MM-DD 형식이어야 합니다.");
        }
        if (date.isAfter(today) || date.isBefore(MIN_DATE)) {
            throw ApiException.invalidRequest("expenseDate", "2000-01-01부터 오늘까지만 허용합니다.");
        }
        return date;
    }

    static Category category(JsonNode node) {
        if (node != null && node.isString()) {
            for (Category category : Category.values()) {
                if (category.name().equals(node.stringValue())) {
                    return category;
                }
            }
        }
        throw ApiException.invalidRequest("category", "허용되지 않는 카테고리입니다.");
    }

    // 생략·null·공백만 있는 메모는 null. 길이는 유니코드 코드 포인트 기준이다.
    static String memo(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw ApiException.invalidRequest("memo", "문자열이어야 합니다.");
        }
        String memo = node.stringValue();
        if (memo.isBlank()) {
            return null;
        }
        // PostgreSQL TEXT는 NUL 문자를 저장할 수 없다.
        if (memo.indexOf('\0') >= 0) {
            throw ApiException.invalidRequest("memo", "허용되지 않는 문자가 있습니다.");
        }
        if (memo.codePointCount(0, memo.length()) > MEMO_MAX_CODE_POINTS) {
            throw ApiException.invalidRequest("memo", "최대 " + MEMO_MAX_CODE_POINTS + "자입니다.");
        }
        return memo;
    }

    // UUID.fromString은 느슨해서(하이픈 없는 짧은 값 허용) 표준 36자 형식을 직접 확인한다.
    static UUID idempotencyKey(String value) {
        if (!UUID_FORMAT.matcher(value).matches()) {
            throw ApiException.invalidRequest("Idempotency-Key", "UUID 형식이어야 합니다.");
        }
        return UUID.fromString(value);
    }
}
