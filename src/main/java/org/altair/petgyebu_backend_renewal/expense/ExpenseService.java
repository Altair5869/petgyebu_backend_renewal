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
import java.util.Optional;
import java.util.UUID;
import org.altair.petgyebu_backend_renewal.common.ApiException;
import org.altair.petgyebu_backend_renewal.common.DbErrors;
import org.altair.petgyebu_backend_renewal.common.Months;
import org.altair.petgyebu_backend_renewal.common.RequestBodies;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class ExpenseService {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

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

    // 생략과 명시적 null을 구분해야 하므로 ExpenseRequest/ExpensePatch가 JsonNode 본문을 직접 파싱한다.
    // idempotencyKey는 선택이다. 본문 검증이 키 조회보다 먼저다.
    // 일부러 @Transactional을 쓰지 않는다. 키 충돌로 insert가 실패해도 다음 조회는 새 트랜잭션이다.
    public CreateResult create(long userId, JsonNode body, String idempotencyKey) {
        ExpenseRequest request = ExpenseRequest.from(body, seoulToday());
        UUID key = idempotencyKey == null ? null : ExpenseFields.idempotencyKey(idempotencyKey);
        if (key != null) {
            Optional<Expense> existing = expenses.findByUserIdAndIdempotencyKey(userId, key);
            if (existing.isPresent()) {
                return replay(existing.get(), request);
            }
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            return new CreateResult(expenses.save(new Expense(null, userId, request.amount(), request.expenseDate(),
                    request.category(), request.memo(), now, now, key)), true);
        } catch (DataIntegrityViolationException e) {
            // 인증 직후 동시 탈퇴로 사용자 행이 사라졌다면 /users/me와 같이 401로 응답한다.
            if (DbErrors.isForeignKeyViolation(e)) {
                throw ApiException.unauthorized();
            }
            // 같은 키의 동시 요청이 먼저 저장됐다. 커밋된 행을 다시 읽어 같은 규칙으로 비교한다.
            if (key != null && DbErrors.isUniqueViolation(e)) {
                Optional<Expense> existing = expenses.findByUserIdAndIdempotencyKey(userId, key);
                if (existing.isPresent()) {
                    return replay(existing.get(), request);
                }
            }
            throw e;
        }
    }

    // 같은 키로 같은 내용이면 기존 지출, 다르면 409.
    private static CreateResult replay(Expense existing, ExpenseRequest request) {
        if (!existing.sameContentAs(request)) {
            throw ApiException.idempotencyKeyReused();
        }
        return new CreateResult(existing, false);
    }

    @Transactional(readOnly = true)
    public Expense get(long userId, long id) {
        return expenses.findByIdAndUserId(id, userId).orElseThrow(ApiException::expenseNotFound);
    }

    @Transactional
    public Expense update(long userId, long id, JsonNode body) {
        RequestBodies.requireObject(body);
        Expense current = get(userId, id);
        Expense updated = ExpensePatch.from(body, seoulToday()).applyTo(current, OffsetDateTime.now(clock));
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
    public Page list(long userId, String monthText, int limit, String cursor, String categoryText,
            String dateText) {
        YearMonth month = Months.parsePastOrCurrent(monthText, clock);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.invalidRequest("limit", "1 이상 " + MAX_LIMIT + " 이하만 허용합니다.");
        }
        Category category = categoryText == null ? null : Category.parse(categoryText);
        LocalDate date = dateText == null ? null : parseDate(dateText, month);
        Cursor c = cursor == null ? null : Cursor.decode(cursor, userId, month);
        // date가 있으면 그 하루만 조회한다.
        Months.Range range = date == null ? Months.range(month) : new Months.Range(date, date.plusDays(1));
        // 한 건 더 읽어 다음 페이지가 실제로 있는지 확인한다.
        List<Expense> rows;
        if (category == null) {
            rows = c == null
                    ? expenses.findFirstPage(userId, range.from(), range.to(), limit + 1)
                    : expenses.findPageAfter(userId, range.from(), range.to(), c.expenseDate(), c.id(), limit + 1);
        } else {
            rows = c == null
                    ? expenses.findFirstPageInCategory(userId, range.from(), range.to(), category.name(), limit + 1)
                    : expenses.findPageAfterInCategory(userId, range.from(), range.to(), category.name(),
                            c.expenseDate(), c.id(), limit + 1);
        }
        if (rows.size() <= limit) {
            return new Page(rows, null);
        }
        List<Expense> items = rows.subList(0, limit);
        Expense last = items.getLast();
        return new Page(items, new Cursor(userId, month, last.expenseDate(), last.id()).encode());
    }

    // YYYY-MM-DD 형식의 해당 월 날짜만 허용한다.
    private static LocalDate parseDate(String text, YearMonth month) {
        try {
            if (text.matches("\\d{4}-\\d{2}-\\d{2}")) {
                LocalDate date = LocalDate.parse(text);
                if (YearMonth.from(date).equals(month)) {
                    return date;
                }
            }
        } catch (DateTimeParseException ignored) {
            // 아래에서 동일한 오류로 처리한다.
        }
        throw ApiException.invalidRequest("date", "YYYY-MM-DD 형식의 그 달 날짜여야 합니다.");
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
}
