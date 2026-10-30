# 달력 보기 백엔드 (M-014) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 달력 화면용 `GET /api/v1/months/{month}/daily-totals`와 지출 목록 `?date=` 필터를 추가한다.

**Architecture:** 날짜별 합계는 기존 `ExpenseRepository.findDayAmounts`로 행을 한 번 읽고 새 도메인 객체 `DailySpending`이 계산한다. 날짜 필터는 `ExpenseService.list`의 조회 범위를 `[date, date+1)`로 좁혀 기존 네 쿼리를 그대로 쓴다. 새 SQL·테이블·의존성은 없다.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Web MVC, Data JDBC), PostgreSQL 16 Testcontainers, JUnit 5 + AssertJ + MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-30-calendar-view-design.md` (앱 `APP-009`는 앱 저장소의 별도 계획이다)

## Global Constraints

- `{month}`는 `Months.parsePastOrCurrent`(2000-01 ~ 서울 기준 현재 월). 오류는 400 `INVALID_REQUEST`(`month`).
- 합계는 DB 집계가 아니라 도메인 객체에서 계산한다. 새 SQL·테이블·캐시·의존성 없음.
- `days`는 지출이 있는 날만 `date` 오름차순. 응답 `{month, days:[{date, spentAmount, expenseCount}]}`.
- `date`는 `YYYY-MM-DD`이고 `{month}` 안이어야 한다. 그 외 400 `INVALID_REQUEST`(`date`). 현재 월의 미래 날짜는 200 빈 목록.
- 지출 목록 검증 순서: `month` → `limit` → `category` → `date` → `cursor`.
- 커서 규칙(사용자·월에 묶임)은 바꾸지 않는다.
- 코드 작성 시 `.agents/skills/karpathy-guidelines/SKILL.md`를 적용한다. 주석은 기존처럼 한국어로 짧게 단다.
- 완료 전 `./gradlew test --no-daemon` 통과.

## Review Focus

- `date=`(빈 값)·`2026-10-1`·` 2026-10-01`처럼 형식이 어긋난 값 → 400 `date` (Task 3 `invalidDateFilterIsRejected`에 포함).
- PATCH로 지출을 다른 달로 옮긴 뒤 두 달의 `daily-totals`가 각각 통계와 맞아야 한다 (Task 2 `daysMatchStatsAfterMovingExpenseAcrossMonths`).
- 서울 월 경계에서 현재 월이 바뀌는 순간 `daily-totals`도 같은 기준이어야 한다 (Task 2 `currentMonthFollowsSeoulAtMonthBoundary`).
- 현재 월의 미래 날짜 필터는 오류가 아니라 빈 목록 (Task 3 `futureDateInCurrentMonthReturnsEmptyList`).
- 같은 날 여러 카테고리 지출이 하나의 칸으로 합쳐져야 한다 (Task 1 `sumsSameDayAcrossCategories`).

---

### Task 1: `DailySpending` 도메인 객체

**Files:**
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/expense/DailySpending.java`
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/expense/DailySpendingTests.java`

**Interfaces:**
- Consumes: `ExpenseDayAmount(LocalDate expenseDate, Category category, long amount)` (기존)
- Produces: `public final class DailySpending` with `public record DayTotal(LocalDate date, long spentAmount, long expenseCount)` and `public static List<DayTotal> of(List<ExpenseDayAmount> rows)` — 지출 있는 날만, 날짜 오름차순, 불변 리스트.

- [ ] **Step 1: Write the failing tests** (`MonthlySpendingTests` 스타일)
  - `emptyRowsGiveNoDays`: `DailySpending.of(List.of())` → `isEmpty()`
  - `sumsSameDayAcrossCategories`: 10-28 FOOD 11000, 10-28 CAFE 12000 → `containsExactly(new DayTotal(2026-10-28, 23000, 2))`
  - `ordersDaysAscendingRegardlessOfRowOrder`: 행 순서 10-30, 10-01, 10-15 → 결과 날짜 `01, 15, 30`
  - `largeDayTotalDoesNotOverflow`: 같은 날 `100_000_000` × 26 → `spentAmount == 2_600_000_000L`

- [ ] **Step 2: Run** `./gradlew test --no-daemon --tests '*DailySpendingTests'` — Expected: 컴파일 실패(`DailySpending` 없음)

- [ ] **Step 3: Implement** `DailySpending.of` — `TreeMap<LocalDate, long[]>`로 합계·건수를 모으고 `DayTotal` 리스트로 바꾼다. 클래스 주석: "조회한 지출 행으로 날짜별 합계·건수를 계산한다. 지출이 있는 날만 날짜 오름차순이다."

- [ ] **Step 4: Run** 같은 명령 — Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add src/main/java/org/altair/petgyebu_backend_renewal/expense/DailySpending.java src/test/java/org/altair/petgyebu_backend_renewal/expense/DailySpendingTests.java
git commit -m "feat: compute per-day spending totals for the calendar (M-014)"
```

---

### Task 2: `GET /api/v1/months/{month}/daily-totals`

**Files:**
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/stats/StatsService.java`
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/stats/StatsController.java`
- Create test: `src/test/java/org/altair/petgyebu_backend_renewal/stats/DailyTotalsApiTests.java` (설정·헬퍼는 `StatsApiTests`의 `reset`, `accessToken`, `seedExpense`, `createExpense`, `patchExpense`, `stats`, `expectInvalidMonth`를 복사)

**Interfaces:**
- Consumes: `DailySpending.of`, `DailySpending.DayTotal` (Task 1), `ExpenseRepository.findDayAmounts(long userId, LocalDate from, LocalDate to)` (기존)
- Produces: `StatsService.DailyTotals(YearMonth month, List<DailySpending.DayTotal> days)` record, `public DailyTotals dailyTotals(long userId, String monthText)` (`@Transactional(readOnly = true)`); controller `record DailyTotalsResponse(String month, List<DailySpending.DayTotal> days)`, `@GetMapping("/api/v1/months/{month}/daily-totals")`.

- [ ] **Step 1: Write the failing tests** (`TestAuthConfig.START` = 서울 2026-10-13 09:00)
  - `returnsOnlyDaysWithSpendingInAscendingOrder`: 10-03 FOOD 5000, 10-01 CAFE 12000, 10-01 FOOD 1000 → STRICT JSON `{"month":"2026-10","days":[{"date":"2026-10-01","spentAmount":13000,"expenseCount":2},{"date":"2026-10-03","spentAmount":5000,"expenseCount":1}]}`, `$.days[0].*` 크기 3
  - `emptyMonthReturnsEmptyDays`: 지출 없음 → `{"month":"2026-10","days":[]}`
  - `includesFirstAndLastDayButNotNeighbourMonths`: 09-30, 09-01, 09-15, 08-31, 10-01 지출 → `2026-09`의 날짜가 `09-01, 09-15, 09-30`만
  - `daysSumToStatsTotals`: 여러 날·카테고리 지출 뒤 `days[*].spentAmount` 합 = stats `spentAmount`, `expenseCount` 합 = stats `expenseCount`
  - `daysMatchStatsAfterMovingExpenseAcrossMonths`: API로 10-05 지출 생성 → PATCH `expenseDate` 2026-09-20 → `2026-10`에 10-05 없음, `2026-09`에 09-20 있음, 두 달 모두 합 = stats
  - `excludesOtherUsers`: 사용자 2 지출은 사용자 1 응답에 없음
  - `currentMonthFollowsSeoulAtMonthBoundary`: `clock.set(Instant.parse("2026-10-31T15:30:00Z"))`(서울 11-01 00:30) → `2026-11` 200, `clock.set("2026-10-31T14:30:00Z")` → `2026-11` 400 `month`
  - `rejectsMalformedFutureAndPre2000Months`: `2026-11`, `2026-1`, `1999-12` → 400 `month`
  - `requiresAuthentication`: 토큰 없이 401

- [ ] **Step 2: Run** `./gradlew test --no-daemon --tests '*DailyTotalsApiTests'` — Expected: FAIL (404 `NOT_FOUND`)

- [ ] **Step 3: Implement** `StatsService.dailyTotals` (`Months.parsePastOrCurrent` → `Months.range` → `findDayAmounts` → `DailySpending.of`)와 컨트롤러 매핑. `month`는 `toString()`으로 내린다(`StatsResponse`와 같음).

- [ ] **Step 4: Run** 같은 명령 — Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add src/main/java/org/altair/petgyebu_backend_renewal/stats src/test/java/org/altair/petgyebu_backend_renewal/stats/DailyTotalsApiTests.java
git commit -m "feat: add daily spending totals API for the calendar (M-014)"
```

---

### Task 3: 지출 목록 `?date=` 필터

**Files:**
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/expense/ExpenseService.java` (`list`, 약 108-134행)
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/expense/ExpenseController.java` (`list`)
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/expense/ExpenseApiTests.java` (기존 `seed`, `list`, `expectInvalid` 사용. `seed`는 금액 1000 고정)

**Interfaces:**
- Consumes: 기존 `findFirstPage`/`findPageAfter`/`findFirstPageInCategory`/`findPageAfterInCategory`
- Produces: `public Page list(long userId, String monthText, int limit, String cursor, String categoryText, String dateText)` — `dateText == null`이면 기존 동작. 컨트롤러 `@RequestParam(required = false) String date`. 다른 호출부가 있으면 `null`을 넘긴다.

- [ ] **Step 1: Write the failing tests**
  - `dateFilterReturnsOnlyThatDayInListOrder`: 10-10 ×2(id 1,3), 10-09(2), 10-11(4) → `&date=2026-10-10` → ids `contains(3, 1)`, `nextCursor` 비어 있음
  - `dateFilterPagesWithCursor`: 10-10 ×3 → `&date=2026-10-10&limit=2` → `(3,2)`, 커서로 다음 → `(1)`, `nextCursor` null
  - `dateFilterCombinesWithCategory`: 10-10 CAFE(1), 10-10 FOOD(2), 10-11 CAFE(3) → `&date=2026-10-10&category=CAFE` → `contains(1)`
  - `dateFilterExcludesOtherUsers`: 사용자 2의 같은 날 지출 제외
  - `futureDateInCurrentMonthReturnsEmptyList`: `&date=2026-10-31` (오늘 10-13) → 200, `items` 비어 있음
  - `invalidDateFilterIsRejected`: `""`, `2026-10-1`, `%202026-10-01`, `2026/10/01`, `2026-02-30`(month `2026-02`), `2026-09-30`(month `2026-10`) → 각각 400 `date`
  - `dateFilterValidationOrder`: `&category=cafe&date=bad` → `category`, `&limit=0&date=bad` → `limit`, `&date=bad&cursor=bad` → `date`, `2026-11` + `&date=bad` → `month`

- [ ] **Step 2: Run** `./gradlew test --no-daemon --tests '*ExpenseApiTests'` — Expected: 새 테스트 FAIL (`date` 무시로 다른 날 포함, 오류 미발생)

- [ ] **Step 3: Implement** — `category` 파싱 다음, 커서 디코드 전에 `date`를 파싱한다. 형식은 `\d{4}-\d{2}-\d{2}`를 먼저 확인하고(`Months`와 같은 이유) `LocalDate.parse`, 실패하거나 `YearMonth.from(date)`가 `month`와 다르면 `ApiException.invalidRequest("date", "YYYY-MM-DD 형식의 그 달 날짜여야 합니다.")`. `date`가 있으면 범위를 `new Months.Range(date, date.plusDays(1))`로 바꾼다. 커서 검증(`Cursor.decode`)은 그대로 월 기준이다.

- [ ] **Step 4: Run** 같은 명령 — Expected: PASS (기존 테스트 포함)

- [ ] **Step 5: Commit**
```bash
git add src/main/java/org/altair/petgyebu_backend_renewal/expense src/test/java/org/altair/petgyebu_backend_renewal/expense/ExpenseApiTests.java
git commit -m "feat: filter month expenses by a single day (M-014)"
```

---

### Task 4: 계약 문서와 전체 검증

**Files:**
- Modify: `docs/00-renewal-development-guide.md` — 8절 API 표에 `| 20 | GET | /api/v1/months/{month}/daily-totals | … |` 추가, "19개 동작, 14개 고유 경로" → "20개 동작, 15개 고유 경로", 11번 행에 선택 `date` 설명, 공통 응답 필드에 날짜별 합계 형식, "지출 목록의 입력 검증 순서"를 `month` → `limit` → `category` → `date` → `cursor`로, 3절 상세 통계에 "달력 / 목록" 탭 한 줄.
- Modify: `docs/02-requirements-features.md` — `F-M01`~`F-M09`, `## F-M09 달력 보기` 절(스펙 1·2절 요약).
- Modify: `docs/03-user-flow.md` — 통계 화면 흐름에 달력 탭.
- Modify: `docs/06-sprint-plan.md` — 6행 작업 ID 목록에 `달력 보기 M-014·APP-009`, 57행 괄호에 `F-M09로 20개·15개`.
- Modify: `docs/08-feature-implementation-map.md` — 기능 표 `F-M09`, API 표 20번 행, 합계 행 `20 | 15 | F-M01~F-M09`.
- Modify: `docs/10-task-backlog.md` — 5행 범위 `M-014`·`APP-009`, `## 후속 — 달력 보기` 표에 `M-014`(`[x]`)·`APP-009`(`[ ]`)와 `M-014` 검증 기록(테스트 이름·개수, 실행 결과).
- Modify: 스펙 상태 줄 `사용자 승인 대기` → `승인됨 (2026-10-30)`.

- [ ] **Step 1:** 위 문서를 갱신하고 `grep -rn "19개 동작\|14개 고유" docs/0*.md docs/10-task-backlog.md`로 남은 옛 수치를 확인한다(이력 괄호 안 표기는 유지). Expected: 현재 수치를 말하는 곳 없음.
- [ ] **Step 2: Run** `./gradlew cleanTest test --no-daemon` — Expected: `BUILD SUCCESSFUL`, 실패 0. 테스트 개수를 백로그 검증 기록에 적는다.
- [ ] **Step 3: Commit**
```bash
git add docs
git commit -m "docs: document the calendar daily totals API and date filter (M-014)"
```
