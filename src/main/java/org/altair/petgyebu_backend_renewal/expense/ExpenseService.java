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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.altair.petgyebu_backend_renewal.common.DbErrors;
import org.altair.petgyebu_backend_renewal.common.Months;
import org.springframework.dao.DataIntegrityViolationException;
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

    public record CreateResult(Expense expense, boolean created) {
    }

    private static final Pattern UUID_FORMAT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    // 생략과 명시적 null을 구분해야 하므로 본문을 JsonNode로 받아 직접 검증한다.
    // idempotencyKey는 선택이다. 본문 검증이 키 조회보다 먼저다.
    // 일부러 @Transactional을 쓰지 않는다. 키 충돌로 insert가 실패해도 다음 조회는 새 트랜잭션이다.
    public CreateResult create(long userId, JsonNode body, String idempotencyKey) {
        requireObject(body);
        long amount = amount(body.get("amount"));
        LocalDate expenseDate = expenseDate(body.get("expenseDate"));
        JsonNode categoryNode = body.get("category");
        Category category = categoryNode == null || categoryNode.isNull() ? Category.OTHER : category(categoryNode);
        String memo = memo(body.get("memo"));
        UUID key = idempotencyKey == null ? null : idempotencyKey(idempotencyKey);
        if (key != null) {
            Optional<Expense> existing = expenses.findByUserIdAndIdempotencyKey(userId, key);
            if (existing.isPresent()) {
                return replay(existing.get(), amount, expenseDate, category, memo);
            }
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            return new CreateResult(
                    expenses.save(new Expense(null, userId, amount, expenseDate, category, memo, now, now, key)), true);
        } catch (DataIntegrityViolationException e) {
            // 인증 직후 동시 탈퇴로 사용자 행이 사라졌다면 /users/me와 같이 401로 응답한다.
            if (DbErrors.isForeignKeyViolation(e)) {
                throw ApiException.unauthorized();
            }
            // 같은 키의 동시 요청이 먼저 저장됐다. 커밋된 행을 다시 읽어 같은 규칙으로 비교한다.
            if (key != null && DbErrors.isUniqueViolation(e)) {
                Optional<Expense> existing = expenses.findByUserIdAndIdempotencyKey(userId, key);
                if (existing.isPresent()) {
                    return replay(existing.get(), amount, expenseDate, category, memo);
                }
            }
            throw e;
        }
    }

    // 같은 키로 같은 내용이면 기존 지출, 다르면 409.
    private static CreateResult replay(Expense existing, long amount, LocalDate expenseDate, Category category,
            String memo) {
        if (existing.amount() != amount || !existing.expenseDate().equals(expenseDate)
                || existing.category() != category || !Objects.equals(existing.memo(), memo)) {
            throw ApiException.idempotencyKeyReused();
        }
        return new CreateResult(existing, false);
    }

    // UUID.fromString은 느슨해서(하이픈 없는 짧은 값 허용) 표준 36자 형식을 직접 확인한다.
    private static UUID idempotencyKey(String value) {
        if (!UUID_FORMAT.matcher(value).matches()) {
            throw ApiException.invalidRequest("Idempotency-Key", "UUID 형식이어야 합니다.");
        }
        return UUID.fromString(value);
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
                current.createdAt(), OffsetDateTime.now(clock), current.idempotencyKey());
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
        YearMonth month = Months.parsePastOrCurrent(monthText, clock);
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
