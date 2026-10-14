package org.altair.petgyebu_backend_renewal.expense;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class ExpenseService {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    static final int MEMO_MAX_CODE_POINTS = 100;
    static final long MAX_AMOUNT = 100_000_000;
    static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);

    public record Page(List<Expense> items, String nextCursor) {
    }

    private final ExpenseRepository expenses;
    private final Clock clock;

    public ExpenseService(ExpenseRepository expenses, Clock clock) {
        this.expenses = expenses;
        this.clock = clock;
    }

    // 생략과 명시적 null을 구분해야 하므로 본문을 JsonNode로 받아 직접 검증한다.
    public Expense create(long userId, JsonNode body) {
        requireObject(body);
        long amount = amount(body.get("amount"));
        LocalDate expenseDate = expenseDate(body.get("expenseDate"));
        JsonNode categoryNode = body.get("category");
        Category category = categoryNode == null || categoryNode.isNull() ? Category.OTHER : category(categoryNode);
        String memo = memo(body.get("memo"));
        OffsetDateTime now = OffsetDateTime.now(clock);
        return expenses.save(new Expense(null, userId, amount, expenseDate, category, memo, now, now));
    }

    @Transactional(readOnly = true)
    public Expense get(long userId, long id) {
        return expenses.findByIdAndUserId(id, userId).orElseThrow(ApiException::expenseNotFound);
    }

    @Transactional
    public Expense update(long userId, long id, JsonNode body) {
        requireObject(body);
        Expense current = get(userId, id);
        Expense updated = new Expense(id, userId,
                body.has("amount") ? amount(body.get("amount")) : current.amount(),
                body.has("expenseDate") ? expenseDate(body.get("expenseDate")) : current.expenseDate(),
                body.has("category") ? category(body.get("category")) : current.category(),
                body.has("memo") ? memo(body.get("memo")) : current.memo(),
                current.createdAt(), OffsetDateTime.now(clock));
        if (expenses.update(id, userId, updated.amount(), updated.expenseDate(), updated.category().name(),
                updated.memo(), updated.updatedAt()) == 0) {
            throw ApiException.expenseNotFound();
        }
        return updated;
    }

    public void delete(long userId, long id) {
        if (expenses.delete(id, userId) == 0) {
            throw ApiException.expenseNotFound();
        }
    }

    @Transactional(readOnly = true)
    public Page list(long userId, String monthText, int limit, String cursor) {
        YearMonth month = month(monthText);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.invalidRequest("limit", "1 이상 " + MAX_LIMIT + " 이하만 허용합니다.");
        }
        LocalDate from = month.atDay(1);
        LocalDate to = month.plusMonths(1).atDay(1);
        // 한 건 더 읽어 다음 페이지가 실제로 있는지 확인한다.
        List<Expense> rows;
        if (cursor == null) {
            rows = expenses.findFirstPage(userId, from, to, limit + 1);
        } else {
            Cursor c = Cursor.decode(cursor, userId, month);
            rows = expenses.findPageAfter(userId, from, to, c.expenseDate(), c.id(), limit + 1);
        }
        if (rows.size() <= limit) {
            return new Page(rows, null);
        }
        List<Expense> items = rows.subList(0, limit);
        Expense last = items.getLast();
        return new Page(items, new Cursor(userId, month, last.expenseDate(), last.id()).encode());
    }

    // 발급한 사용자·월에서만 유효한 불투명 커서. base64url("userId:YYYY-MM:YYYY-MM-DD:id")
    record Cursor(long userId, YearMonth month, LocalDate expenseDate, long id) {

        String encode() {
            String raw = userId + ":" + month + ":" + expenseDate + ":" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String token, long userId, YearMonth month) {
            Cursor cursor;
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)
                        .split(":", -1);
                if (parts.length != 4) {
                    throw ApiException.invalidCursor();
                }
                cursor = new Cursor(Long.parseLong(parts[0]), YearMonth.parse(parts[1]), LocalDate.parse(parts[2]),
                        Long.parseLong(parts[3]));
            } catch (IllegalArgumentException | DateTimeParseException e) {
                throw ApiException.invalidCursor();
            }
            // 날짜가 월 안에 있어야 한다. 극단적인 날짜가 DB까지 가지 않게 막는다.
            if (cursor.userId != userId || !cursor.month.equals(month)
                    || !YearMonth.from(cursor.expenseDate).equals(month)) {
                throw ApiException.invalidCursor();
            }
            return cursor;
        }
    }

    private LocalDate seoulToday() {
        return LocalDate.ofInstant(clock.instant(), SEOUL);
    }

    private YearMonth month(String text) {
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
        if (month.isAfter(YearMonth.from(seoulToday())) || month.isBefore(YearMonth.from(MIN_DATE))) {
            throw ApiException.invalidRequest("month", "2000-01부터 현재 월까지만 조회할 수 있습니다.");
        }
        return month;
    }

    private static void requireObject(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 본문을 읽을 수 없습니다.", null);
        }
    }

    private static long amount(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 1
                || node.longValue() > MAX_AMOUNT) {
            throw ApiException.invalidRequest("amount", "1 이상 " + MAX_AMOUNT + " 이하의 정수여야 합니다.");
        }
        return node.longValue();
    }

    private LocalDate expenseDate(JsonNode node) {
        if (node == null || !node.isString() || !node.stringValue().matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw ApiException.invalidRequest("expenseDate", "YYYY-MM-DD 형식이어야 합니다.");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(node.stringValue());
        } catch (DateTimeParseException e) {
            throw ApiException.invalidRequest("expenseDate", "YYYY-MM-DD 형식이어야 합니다.");
        }
        if (date.isAfter(seoulToday()) || date.isBefore(MIN_DATE)) {
            throw ApiException.invalidRequest("expenseDate", "2000-01-01부터 오늘까지만 허용합니다.");
        }
        return date;
    }

    private static Category category(JsonNode node) {
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
    private static String memo(JsonNode node) {
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
}
