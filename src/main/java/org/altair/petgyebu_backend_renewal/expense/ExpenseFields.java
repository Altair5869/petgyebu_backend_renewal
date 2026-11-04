package org.altair.petgyebu_backend_renewal.expense;

import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Pattern;
import org.altair.petgyebu_backend_renewal.common.ApiException;

// 지출 요청의 공유 상수와 Idempotency-Key 헤더 검증. 본문 필드 검증은 DTO 제약이 맡는다.
final class ExpenseFields {

    static final int MEMO_MAX_CODE_POINTS = 100;
    static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);

    private static final Pattern UUID_FORMAT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private ExpenseFields() {
    }

    // UUID.fromString은 느슨해서(하이픈 없는 짧은 값 허용) 표준 36자 형식을 직접 확인한다.
    static UUID idempotencyKey(String value) {
        if (!UUID_FORMAT.matcher(value).matches()) {
            throw ApiException.invalidRequest("Idempotency-Key", "UUID 형식이어야 합니다.");
        }
        return UUID.fromString(value);
    }
}
