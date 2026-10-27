# 소비 인사이트 API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /api/v1/insights`로 카테고리별 이번 주 vs 지난주 같은 요일 범위 비교와 현재 월 예산 소진 예측을 내려준다.

**Architecture:** 새 패키지 `insight`. DB는 `[min(현재 월 1일, 지난주 월요일), 오늘]` 지출 행 `(expense_date, category, amount)`을 한 번 조회만 한다. 계산은 DB 없는 도메인 객체 `WeeklyComparison`, `BudgetForecast`가 하고, `InsightService`는 조회·조합만 한다. 월 합계는 기존 `MonthlySpending`을 재사용해 홈 `spentAmount`와 같은 값을 보장한다.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring Data JDBC, PostgreSQL 16, JUnit 5 + AssertJ + Testcontainers

**Spec:** `docs/superpowers/specs/2026-10-27-spending-insights-design.md`

## Global Constraints

- 아키텍처(`2026-10-27-service-rules-refactor` 방식, AGENTS.md 18행): DB 쿼리는 행 조회만 한다(`SUM`·`GROUP BY`·`COALESCE` 등 집계·계산 금지). 합계·비교·예측은 DB 없이 테스트하는 도메인 객체가 계산하고, Service는 조회·조합과 이름 붙은 규칙만 가진다. 컨트롤러는 응답 변환만 한다.
- 시간은 주입된 `Clock` + `ZoneId.of("Asia/Seoul")`. 주는 월요일 시작, 날짜 범위는 양 끝 포함(응답), 조회는 `[from, to)`.
- 기존 15개 API 요청·응답, DB 스키마·인덱스 변경 없음. 새 의존성 없음.
- 금액은 `long`. 예측 소진일은 정수 올림 나눗셈 `ceil(budget × d / spent)`.
- 펫 선택을 요구하지 않고, 예산이 없어도 200(`NO_BUDGET`). 인증 실패만 401.
- 기존 테스트는 고치지 않는다. 완료 전 `./gradlew test --no-daemon` 전체 통과, PR 전 qa-engineer 검토.
- `$karpathy-guidelines` 적용. 주석은 한국어, 주변 밀도에 맞춘다.

## Review Focus

1. 서울 00:00~08:59(UTC 전날)에 `today`·주·월이 서울 기준인지 → Task 3 `todayFollowsSeoulWhileUtcIsYesterday`.
2. 진행 중인 이번 주가 지난주 7일 전체와 비교되지 않는지(같은 요일 범위) → Task 1 `tuesdayComparesMondayToTuesdayOnly`.
3. 달을 걸친 주에서 지난달 날짜도 주간 비교에 들어가는지, 예측 합계에는 안 들어가는지 → Task 1 `weekSpanningMonthsKeepsCalendarWeek`, Task 3 `weekSpanningMonthsUsesPreviousMonthRowsOnlyForWeekly`.
4. 2일차에 이미 예산을 다 쓴 경우 `TOO_EARLY`로 숨기지 않는지 → Task 2 `budgetReachedIsShownEvenEarlyInMonth`.
5. 다른 사용자의 지출·예산이 섞이지 않는지 → Task 3 `otherUsersRowsAreExcluded`.

---

### Task 1: `WeeklyComparison` 도메인 객체

**Files:**
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/expense/ExpenseDayAmount.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/insight/WeeklyComparison.java`
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/insight/WeeklyComparisonTests.java`

**Interfaces:**
- Produces:
  - `record ExpenseDayAmount(LocalDate expenseDate, Category category, long amount)` (expense 패키지, public)
  - `record DateRange(LocalDate from, LocalDate to)` — `WeeklyComparison` 안의 public 중첩 record, 양 끝 포함
  - `static DateRange WeeklyComparison.thisWeek(LocalDate today)` → `[today가 속한 주 월요일, today]`
  - `static DateRange WeeklyComparison.lastWeek(LocalDate today)` → `thisWeek(today)`의 두 날짜를 각각 7일 앞으로
  - `static WeeklyComparison WeeklyComparison.of(LocalDate today, List<ExpenseDayAmount> rows)` — 범위 밖 행은 무시
  - `DateRange thisWeek()`, `DateRange lastWeek()`, `List<CategoryChange> categories()`
  - `record CategoryChange(Category category, long thisWeekAmount, long lastWeekAmount, long changeAmount)` — 중첩, public
  - `categories()`: 두 범위 중 한쪽에라도 지출이 있는 카테고리, `changeAmount` 내림차순, 동률은 `Category` 선언 순서

참고: 2026-10-12는 월요일, 10-13 화, 10-18 일, 11-01 일.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
@Test void tuesdayComparesMondayToTuesdayOnly() {
    LocalDate today = LocalDate.of(2026, 10, 13);
    WeeklyComparison w = WeeklyComparison.of(today, List.of(
            row("2026-10-12", CAFE, 15000), row("2026-10-13", CAFE, 6000), row("2026-10-13", FOOD, 5000),
            row("2026-10-05", CAFE, 8000), row("2026-10-06", TRANSPORT, 3000),
            row("2026-10-07", FOOD, 99999), row("2026-10-04", FOOD, 77777)));   // 지난주 수요일·그 전 주 일요일: 제외
    assertThat(w.thisWeek()).isEqualTo(new DateRange(LocalDate.of(2026, 10, 12), today));
    assertThat(w.lastWeek()).isEqualTo(new DateRange(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6)));
    assertThat(w.categories()).containsExactly(
            new CategoryChange(CAFE, 21000, 8000, 13000),
            new CategoryChange(FOOD, 5000, 0, 5000),
            new CategoryChange(TRANSPORT, 0, 3000, -3000));
}
@Test void mondayComparesSingleDays() {
    LocalDate mon = LocalDate.of(2026, 10, 12);
    WeeklyComparison w = WeeklyComparison.of(mon, List.of());
    assertThat(w.thisWeek()).isEqualTo(new DateRange(mon, mon));
    assertThat(w.lastWeek()).isEqualTo(new DateRange(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5)));
    assertThat(w.categories()).isEmpty();
}
@Test void sundayComparesWholeWeeks() {
    WeeklyComparison w = WeeklyComparison.of(LocalDate.of(2026, 10, 18), List.of());
    assertThat(w.thisWeek()).isEqualTo(new DateRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18)));
    assertThat(w.lastWeek()).isEqualTo(new DateRange(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11)));
}
@Test void weekSpanningMonthsKeepsCalendarWeek() {
    WeeklyComparison w = WeeklyComparison.of(LocalDate.of(2026, 11, 1),
            List.of(row("2026-10-26", CAFE, 1000), row("2026-11-01", CAFE, 2000), row("2026-10-19", CAFE, 500)));
    assertThat(w.thisWeek()).isEqualTo(new DateRange(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1)));
    assertThat(w.categories()).containsExactly(new CategoryChange(CAFE, 3000, 500, 2500));
}
@Test void equalChangesFollowCategoryOrder() {
    WeeklyComparison w = WeeklyComparison.of(LocalDate.of(2026, 10, 13),
            List.of(row("2026-10-13", CAFE, 1000), row("2026-10-13", FOOD, 1000)));
    assertThat(w.categories()).extracting(CategoryChange::category).containsExactly(FOOD, CAFE);
}
```

(`row(date, category, amount)`은 `new ExpenseDayAmount(LocalDate.parse(date), category, amount)` 헬퍼, `Category.*` static import.)

- [ ] **Step 2: 실행, 실패 확인** — `./gradlew test --no-daemon --tests '*WeeklyComparisonTests'` → 컴파일 실패
- [ ] **Step 3: 구현** — `EnumMap<Category, long[]>` 같은 누적 대신 두 `EnumMap<Category, Long>`(이번 주·지난주)로 더해 가독성을 우선한다. `DayOfWeek.MONDAY`, `TemporalAdjusters.previousOrSame`.
- [ ] **Step 4: 실행, 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat: compare this week's category spending with the same days last week"`

---

### Task 2: `BudgetForecast` 도메인 객체

**Files:**
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/insight/BudgetForecast.java`
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/insight/BudgetForecastTests.java`

**Interfaces:**
- Produces:
  - `enum BudgetForecast.Status { WILL_EXHAUST, WITHIN_BUDGET, TOO_EARLY, BUDGET_REACHED, NO_BUDGET }` (public 중첩)
  - `static BudgetForecast BudgetForecast.of(LocalDate today, Long budgetAmount, long spentAmount)` — `budgetAmount == null`이면 예산 없음
  - `Status status()`, `Long dailyAverageAmount()`, `LocalDate exhaustionDate()` (해당 없으면 `null`)
  - 판정 순서와 값은 스펙 3.2 표 그대로. `d = today.getDayOfMonth()`, 그 달 일수 `today.lengthOfMonth()`, 소진일 `n = (budget × d + spent - 1) / spent`(정수 올림), `dailyAverageAmount = spent / d`(버림). 상수 `TOO_EARLY_LAST_DAY = 3`.

- [ ] **Step 1: 실패하는 테스트 작성** (10월은 31일)

```java
@Test void noBudgetEvenOnFirstDay() { expect(of("2026-10-01", null, 0), NO_BUDGET, null, null); }
@Test void budgetReachedIsShownEvenEarlyInMonth() { expect(of("2026-10-02", 100000L, 100000), BUDGET_REACHED, null, null); }
@Test void exactlySpentEqualsBudgetIsReached() { expect(of("2026-10-13", 300000L, 300000), BUDGET_REACHED, null, null); }
@Test void dayThreeIsTooEarlyDayFourIsForecast() {
    expect(of("2026-10-03", 100000L, 30000), TOO_EARLY, null, null);
    expect(of("2026-10-04", 100000L, 40000), WILL_EXHAUST, 10000L, "2026-10-10");
}
@Test void noSpendingIsWithinBudget() { expect(of("2026-10-13", 300000L, 0), WITHIN_BUDGET, 0L, null); }
@Test void paceReachingBudgetInsideMonthGivesDate() { expect(of("2026-10-13", 300000L, 130000), WILL_EXHAUST, 10000L, "2026-10-30"); }
@Test void paceBeyondMonthEndIsWithinBudget() { expect(of("2026-10-13", 300000L, 120000), WITHIN_BUDGET, 9230L, null); }
@Test void reachingBudgetExactlyOnLastDayIsExhaust() { expect(of("2026-10-10", 310000L, 100000), WILL_EXHAUST, 10000L, "2026-10-31"); }
@Test void largeAmountsDoNotOverflow() { expect(of("2026-10-30", 100_000_000L, 99_999_999), WILL_EXHAUST, 3_333_333L, "2026-10-31"); }
```

(`of(date, budget, spent)` = `BudgetForecast.of(LocalDate.parse(date), budget, spent)`, `expect(f, status, avg, date)`는 세 접근자를 `assertThat`으로 확인하는 헬퍼, `date == null`이면 `exhaustionDate()`가 null.)

- [ ] **Step 2: 실행, 실패 확인** — `./gradlew test --no-daemon --tests '*BudgetForecastTests'` → 컴파일 실패
- [ ] **Step 3: 구현** — 판정 순서를 스펙 표 순서의 early return으로 쓴다. 주석에 "1~3일차는 지출 한 번에 예측이 과해지므로 숨긴다".
- [ ] **Step 4: 실행, 통과 확인** — PASS
- [ ] **Step 5: Commit** — `git commit -m "feat: forecast when the current month budget runs out"`

---

### Task 3: 조회, `InsightService`, `GET /api/v1/insights`

**Files:**
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/expense/ExpenseRepository.java` (`findDayAmounts` 추가)
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/insight/InsightService.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/insight/InsightController.java`
- Modify: `perf/explain.sql` (`findDayAmounts` 블록 추가, 헤더 규칙대로 `@Query`와 같은 본문)
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/insight/InsightApiTests.java` (`StatsApiTests`의 Testcontainers·`MutableClock`·`accessToken`·`seedExpense`·`setBudget`·`setPet` 패턴 복사)

**Interfaces:**
- Consumes: Task 1 `ExpenseDayAmount`, `WeeklyComparison`; Task 2 `BudgetForecast`; 기존 `Months.current/range`, `MonthlySpending`, `ExpenseAmount`, `MonthlyBudgetRepository.find`.
- Produces:
  - `List<ExpenseDayAmount> ExpenseRepository.findDayAmounts(long userId, LocalDate from, LocalDate to)` — `SELECT expense_date, category, amount FROM expenses WHERE user_id = :userId AND expense_date >= :from AND expense_date < :to`
  - `record InsightService.Insights(LocalDate today, WeeklyComparison weekly, BudgetForecast forecast)`, `Insights InsightService.insights(long userId)` — `@Transactional(readOnly = true)`
  - 응답 JSON은 스펙 3절 예시와 같은 필드 이름·중첩: `today`, `weekly.thisWeek.{from,to}`, `weekly.lastWeek.{from,to}`, `weekly.categories[].{category,thisWeekAmount,lastWeekAmount,changeAmount}`, `forecast.{status,dailyAverageAmount,exhaustionDate}`. null 필드도 키를 내린다.

- [ ] **Step 1: 실패하는 API 테스트 작성** (START = 서울 2026-10-13 화 09:00)

```java
@Test void returnsWeeklyAndForecastWithExactShape() {
    // 사용자 1: 예산 300000, 10-05 CAFE 8000, 10-12 CAFE 15000, 10-13 CAFE 6000, 10-13 FOOD 5000, 10-02 FOOD 96000
    // 월 합계 130000 → WILL_EXHAUST, 10000, 2026-10-30
    insights(a).andExpect(status().isOk()).andExpect(content().json("""
        {"today":"2026-10-13",
         "weekly":{"thisWeek":{"from":"2026-10-12","to":"2026-10-13"},"lastWeek":{"from":"2026-10-05","to":"2026-10-06"},
                   "categories":[{"category":"CAFE","thisWeekAmount":21000,"lastWeekAmount":8000,"changeAmount":13000},
                                 {"category":"FOOD","thisWeekAmount":5000,"lastWeekAmount":0,"changeAmount":5000}]},
         "forecast":{"status":"WILL_EXHAUST","dailyAverageAmount":10000,"exhaustionDate":"2026-10-30"}}
        """, JsonCompareMode.STRICT));
}
@Test void withoutPetAndBudgetReturnsNoBudget() {
    // 펫·예산 없음 → 200, forecast {"status":"NO_BUDGET","dailyAverageAmount":null,"exhaustionDate":null}
}
@Test void otherUsersRowsAreExcluded() {
    // 사용자 2의 같은 날짜 지출·예산이 사용자 1 응답에 반영되지 않음
}
@Test void weekSpanningMonthsUsesPreviousMonthRowsOnlyForWeekly() {
    // clock = 2026-10-31T15:00:00Z (서울 11-01 일 00:00). 예산 2026-11-01 2500.
    // 10-26 CAFE 1000, 11-01 CAFE 2000, 10-19 CAFE 500
    // weekly CAFE 3000/500/2500, forecast TOO_EARLY (1일차, 11월 합계 2000 < 2500).
    // 10월 행이 월 합계에 섞이면 3500 ≥ 2500이라 BUDGET_REACHED가 되므로 이 단언이 그 실수를 잡는다.
}
@Test void todayFollowsSeoulWhileUtcIsYesterday() {
    // clock = 2026-10-12T15:30:00Z (UTC 10-12 월, 서울 10-13 화 00:30) → today "2026-10-13", thisWeek 10-12~10-13
}
@Test void requiresAuthentication() { /* GET 토큰 없이 → 401 UNAUTHORIZED */ }
```

주석 처리된 테스트는 각 줄의 데이터와 기대값 그대로 `jsonPath`로 단언한다.

- [ ] **Step 2: 실행, 실패 확인** — `./gradlew test --no-daemon --tests '*InsightApiTests'` → 404/컴파일 실패
- [ ] **Step 3: 구현**
  - `InsightService.insights`: 서울 `today` → `YearMonth.from(today)`와 `Months.range` → 조회 `from = min(range.from(), WeeklyComparison.lastWeek(today).from())`, `to = today.plusDays(1)` → `WeeklyComparison.of(today, rows)` → 현재 월 행만 `ExpenseAmount`로 바꿔 `MonthlySpending.of(...).total()` → `budgets.find(userId, range.from())` → `BudgetForecast.of(today, budgetOrNull, spent)`.
  - `today`는 `LocalDate.ofInstant(clock.instant(), SEOUL)`; `Months.current(clock)`와 같은 날짜 기준이어야 한다.
  - 컨트롤러는 `StatsController`처럼 응답 record로 변환만 한다.
- [ ] **Step 4: 전체 테스트** — `./gradlew test --no-daemon` → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat: add GET /api/v1/insights for weekly comparison and budget forecast"`

---

### Task 4: 문서 갱신

**Files:**
- Modify: `docs/00-renewal-development-guide.md` (8절 API 표 16번 행 `GET /api/v1/insights`, "15개 동작, 11개 고유 경로" → "16개 동작, 12개 고유 경로", 공통 응답 필드에 인사이트 응답 한 줄, 4절 또는 별도 짧은 절에 예측 상태 규칙 요약 — 스펙 3.1·3.2 표 기준)
- Modify: `docs/02-requirements-features.md` (기능 ID 범위 문구를 `F-M01`~`F-M07`로, `## F-M07 소비 인사이트` 절 추가)
- Modify: `docs/08-feature-implementation-map.md` (기능 표에 `F-M07`, API 표에 16번 행)
- Modify: `docs/10-task-backlog.md` (범위 문구 갱신, `M-011` 백엔드 인사이트 API [x]와 검증 기록, `APP-006` 인사이트 화면 [ ] 의존 `M-011`)
- Modify: `docs/09-db-design.md` 4절 (인사이트가 같은 인덱스로 최대 약 6주 범위 행을 조회한다는 한 줄)

- [ ] **Step 1: 문서 수정** — 기존 문서의 표·문체를 따른다. 스펙에 없는 규칙을 만들지 않는다.
- [ ] **Step 2: 확인** — `grep -n "15개 동작" docs/*.md` 결과가 `docs/04-review-log.md`·`archive` 외에 없어야 한다. `./gradlew test --no-daemon` → PASS
- [ ] **Step 3: Commit** — `git commit -m "docs: add spending insights API to the contract and backlog"`
