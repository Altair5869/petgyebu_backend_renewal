package org.altair.petgyebu_backend_renewal.common;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

// 경로의 {month} 해석. 현재 월은 Asia/Seoul 기준이다.
public final class Months {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final YearMonth MIN_MONTH = YearMonth.of(2000, 1);

    // 월 범위 [from, to). to는 다음 달 첫날이다.
    public record Range(LocalDate from, LocalDate to) {
    }

    private Months() {
    }

    public static YearMonth current(Clock clock) {
        return YearMonth.from(clock.instant().atZone(SEOUL));
    }

    public static Range range(YearMonth month) {
        return new Range(month.atDay(1), month.plusMonths(1).atDay(1));
    }

    // 2000-01부터 서울 기준 현재 월까지의 YYYY-MM만 허용한다.
    public static YearMonth parsePastOrCurrent(String text, Clock clock) {
        // 형식을 먼저 고정한다. 기본 파서는 부호·5자리 이상 연도도 받아들인다.
        if (!text.matches("\\d{4}-\\d{2}")) {
            throw ApiException.invalidRequest("month", "YYYY-MM 형식이어야 합니다.");
        }
        YearMonth month;
        try {
            month = YearMonth.parse(text);
        } catch (DateTimeParseException e) {
            throw ApiException.invalidRequest("month", "YYYY-MM 형식이어야 합니다.");
        }
        if (month.isAfter(current(clock)) || month.isBefore(MIN_MONTH)) {
            throw ApiException.invalidRequest("month", "2000-01부터 현재 월까지만 허용합니다.");
        }
        return month;
    }
}
