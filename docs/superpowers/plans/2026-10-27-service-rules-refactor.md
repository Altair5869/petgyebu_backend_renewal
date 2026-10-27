# Service 규칙 중심 구조 개선 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Service 메서드가 비즈니스 규칙 목록으로 읽히도록 형식 검사는 요청 객체로, 월 합산은 도메인 객체로 옮기고 DB는 행 조회만 하게 한다.

**Architecture:** `ExpenseRepository`는 한 달의 `(category, amount)` 행만 돌려주고 `MonthlySpending`이 총액·건수·카테고리별 합계를 계산한다(홈·통계 공용). `JsonNode` 파싱은 요청 record의 `from(...)` 정적 팩토리가 하고, Service가 지금 검증이 일어나는 지점에서 그것을 호출한 뒤 이름 붙은 `validate…` 규칙과 Repository 호출을 나열한다. 탈퇴용 `DELETE`는 소유 Repository로 옮긴다.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring Data JDBC, PostgreSQL 16, JUnit 5 + Testcontainers, k6 2.3.0

**Spec:** `docs/superpowers/specs/2026-10-27-service-rules-refactor-design.md`

## Global Constraints

- API 계약 불변: 상태 코드, 오류 `code`, `fields[].field`, 메시지, 응답 필드·값·정렬을 바꾸지 않는다.
- 여러 값이 동시에 틀린 요청의 첫 오류 순서를 유지한다(스펙 4절 표).
- 기존 테스트는 고치지 않는다. 클래스 이동에 따른 컴파일 수정만 허용한다.
- 동시성 보호 유지: 멱등키 `UNIQUE` 충돌 재조회, 예산·로그인 `ON CONFLICT`, 탈퇴 한 트랜잭션 + FK 위반 3회 재시도, 수정·삭제 `WHERE id AND user_id`. 지출 수정을 `save()`로 바꾸지 않는다.
- 마이그레이션·인덱스 변경 없음. JPA·QueryDSL·Lombok·새 의존성 없음.
- 느려져도 3.1 구조(행 조회 → Service 집계)를 유지한다. 홈만 `SUM`으로 되돌리지 않는다.
- 시간은 `Asia/Seoul` 고정, 월 범위는 `[첫날, 다음 달 첫날)`.
- 완료 전 `./gradlew test --no-daemon` 전체 통과. `$karpathy-guidelines` 적용, PR 전 qa-engineer 검토.

## Review Focus

1. 지출 POST에서 amount와 category가 동시에 틀림 → `amount` 오류가 먼저(Task 1 `createReportsFirstInvalidFieldInBodyOrder`).
2. 없는 지출에 대한 PATCH에 잘못된 필드 → 404, 배열 본문이면 400(Task 1 `patchChecksBodyShapeThenExistenceThenFields`).
3. 과거 월 예산 PUT에 잘못된 본문 → `month` 오류(Task 1 `putChecksMonthBeforeBody`).
4. 같은 멱등키 재요청에서 메모가 공백만 vs 생략 → 같은 내용으로 보고 200(Task 4 `blankMemoReplayEqualsOmittedMemo`).
5. 지출 0건 달의 홈·통계 → 0원·0건·빈 카테고리, 25억 원대 합계 오버플로 없음(기존 API 테스트 + Task 2 `MonthlySpendingTests`).

---

### Task 1: 오류 순서를 고정하는 테스트

리팩터링 전 현재 동작을 고정한다. 이 테스트들은 **지금 코드에서 바로 통과해야 한다**(실패하면 테스트가 현재 동작을 잘못 적은 것이다).

**Files:**
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/expense/ExpenseApiTests.java` (기존 헬퍼 `create`, `update`, `expectInvalid`, `expectNotFound`, `body` 사용)
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/budget/BudgetApiTests.java` (`putBudget`, `expectInvalid`)

**Interfaces:** 없음

- [ ] **Step 1: 테스트 추가**

```java
// ExpenseApiTests
@Test
void createReportsFirstInvalidFieldInBodyOrder() throws Exception {
    String a = accessToken("a");
    expectInvalid(create(a, "{\"amount\":0,\"expenseDate\":\"x\",\"category\":\"X\",\"memo\":5}"), "amount");
    expectInvalid(create(a, "{\"amount\":1,\"expenseDate\":\"2026-10-14\",\"category\":\"X\",\"memo\":5}"), "expenseDate");
    expectInvalid(create(a, "{\"amount\":1,\"expenseDate\":\"2026-10-13\",\"category\":\"X\",\"memo\":5}"), "category");
    // 잘못된 본문이 잘못된 Idempotency-Key보다 먼저다.
    expectInvalid(mvc.perform(post("/api/v1/expenses").header("Authorization", "Bearer " + a)
            .header("Idempotency-Key", "bad").contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":0,\"expenseDate\":\"2026-10-13\"}")), "amount");
    assertThat(expenseCount()).isZero();
}

@Test
void patchChecksBodyShapeThenExistenceThenFields() throws Exception {
    String a = accessToken("a");
    update(a, 99, "[]").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    expectNotFound(update(a, 99, "{\"amount\":0}"));
    create(a, body(3000, "2026-10-12", "HEALTH", null));
    expectInvalid(update(a, 1, "{\"amount\":0,\"category\":\"X\"}"), "amount");
    expectInvalid(update(a, 1, "{\"expenseDate\":\"2026-10-14\",\"memo\":5}"), "expenseDate");
}

// BudgetApiTests
@Test
void putChecksMonthBeforeBody() throws Exception {
    String a = accessToken("a");
    expectInvalid(putBudget(a, "2026-09", "[]"), "month");
    expectInvalid(putBudget(a, "2026-9", "{\"amount\":0}"), "month");
    assertThat(budgetCount()).isZero();
}
```

- [ ] **Step 2: 실행, 통과 확인**

Run: `./gradlew test --no-daemon --tests '*ExpenseApiTests' --tests '*BudgetApiTests'`
Expected: PASS (현재 코드 기준)

- [ ] **Step 3: Commit**

```bash
git add src/test
git commit -m "test: pin validation error order before service refactor"
```

---

### Task 2: 월 집계를 행 조회 + `MonthlySpending`으로

**Files:**
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/expense/ExpenseAmount.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/expense/MonthlySpending.java`
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/expense/ExpenseRepository.java` (`sumAmount`·`CategoryTotal`·`sumByCategory` 삭제, `findAmounts` 추가)
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/common/Months.java` (`Range`, `range` 추가)
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/home/HomeService.java`
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/stats/StatsService.java`
- Modify: `perf/explain.sql` (`sumAmount`·`sumByCategory` 블록 → `findAmounts` 블록 하나)
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/expense/MonthlySpendingTests.java` (DB 없는 단위 테스트)

**Interfaces:**
- Produces:
  - `record ExpenseAmount(Category category, long amount)`
  - `List<ExpenseAmount> ExpenseRepository.findAmounts(long userId, LocalDate from, LocalDate to)` — SQL은 스펙 3.1 그대로(`SELECT category, amount FROM expenses WHERE user_id = :userId AND expense_date >= :from AND expense_date < :to`)
  - `final class MonthlySpending`: `static MonthlySpending of(List<ExpenseAmount> rows)`, `long total()`, `long count()`, `List<MonthlySpending.CategoryTotal> categories()`; `record CategoryTotal(Category category, long spentAmount, long expenseCount)`. `categories()`는 지출 있는 카테고리만, `Category` 선언 순서.
  - `Months.Range(LocalDate from, LocalDate to)` record, `static Months.Range range(YearMonth month)` → `[month.atDay(1), month.plusMonths(1).atDay(1))`

- [ ] **Step 1: 실패하는 단위 테스트 작성** (`MonthlySpendingTests`)

```java
@Test void emptyMonthIsZero() {
    MonthlySpending s = MonthlySpending.of(List.of());
    assertThat(s.total()).isZero(); assertThat(s.count()).isZero(); assertThat(s.categories()).isEmpty();
}
@Test void sumsAndCountsPerCategoryInDeclarationOrder() {
    MonthlySpending s = MonthlySpending.of(List.of(new ExpenseAmount(Category.OTHER, 500),
            new ExpenseAmount(Category.FOOD, 1000), new ExpenseAmount(Category.FOOD, 2000)));
    assertThat(s.total()).isEqualTo(3500); assertThat(s.count()).isEqualTo(3);
    assertThat(s.categories()).containsExactly(new MonthlySpending.CategoryTotal(Category.FOOD, 3000, 2),
            new MonthlySpending.CategoryTotal(Category.OTHER, 500, 1));
}
@Test void largeTotalsDoNotOverflow() {
    List<ExpenseAmount> rows = Collections.nCopies(26, new ExpenseAmount(Category.FOOD, 100_000_000));
    assertThat(MonthlySpending.of(rows).total()).isEqualTo(2_600_000_000L);
}
```

- [ ] **Step 2: 실행, 컴파일 실패 확인**

Run: `./gradlew test --no-daemon --tests '*MonthlySpendingTests'`
Expected: FAIL (`MonthlySpending` 없음)

- [ ] **Step 3: `ExpenseAmount`, `MonthlySpending`, `Months.range`, `ExpenseRepository.findAmounts` 구현**

`MonthlySpending`은 `EnumMap<Category, …>`로 모아 선언 순서를 보장한다. 주석에 "지출이 없으면 0원·0건·빈 목록"을 남긴다.

- [ ] **Step 4: `HomeService`·`StatsService` 전환**
  - `HomeService.home`: `Months.range(month)`로 범위를 얻고 `MonthlySpending.of(expenses.findAmounts(...)).total()`을 `BudgetUsage`에 넣는다. 펫 → 예산 순서 유지.
  - `StatsService.stats`: 한 번의 `findAmounts`로 `MonthlySpending`을 만들고 `spentAmount = total()`, `expenseCount = count()`, 카테고리는 `categories()`를 기존 `ORDER`(금액 내림차순, 동률 `Category` 순)와 `share`(HALF_UP 1자리)로 변환한다. `ORDER`의 타입은 `MonthlySpending.CategoryTotal`로 바꾼다. `@Transactional(readOnly = true)`로 낮추고 REPEATABLE READ 주석은 "한 조회로 합계·카테고리를 함께 계산한다"로 바꾼다.

- [ ] **Step 5: `perf/explain.sql` 갱신** — `== sumAmount`, `== sumByCategory` 블록을 `== findAmounts` 블록 하나(위 SQL, `:uid`/`:'m_from'`/`:'m_to'`)로 바꾼다.

- [ ] **Step 6: 전체 테스트 통과 확인**

Run: `./gradlew test --no-daemon`
Expected: PASS (`HomeApiTests`, `StatsApiTests` 수정 없이)

- [ ] **Step 7: Commit**

```bash
git add src perf/explain.sql
git commit -m "refactor: compute monthly totals in MonthlySpending from fetched expense rows"
```

---

### Task 3: 집계 이동 성능 비교 측정

같은 장비·같은 세션에서 이전(`develop` HEAD)과 이후(Task 2 커밋)를 연달아 재서 비교한다. 결과와 무관하게 구조는 유지하고 수치를 그대로 기록한다.

**Files:**
- Create: `perf/results/2026-10-27-monthly-spending.md`
- Create: `perf/results/2026-10-27-monthly-spending/explain/uid{60,1,100}_m{0,-6}.txt`, `.../k6/{before,after}_<tier>_<endpoint>.json`

**Interfaces:** Consumes Task 2의 `findAmounts` SQL.

- [ ] **Step 1: 측정 DB 준비** — `perf/README.md` 절차 1·3(사용자 1,000 × 12개월). 컨테이너 이름 `petgyebu-perf-pg`. `PETGYEBU_JWT_SECRET`은 환경 변수에서 읽고 출력하지 않는다.
- [ ] **Step 2: 이전 측정** — `git worktree add ../petgyebu-before develop`에서 절차 2로 앱 실행(포트 8090), k6로 `normal home`, `normal stats_current`, `normal stats_past`, `heavy home`, `heavy stats_current` 측정. summary는 `jq 'del(.setup_data)'` 후 저장. 앱 종료.
- [ ] **Step 3: 이후 측정** — 이 브랜치에서 같은 5개 시나리오(시드 다시 하지 않음, create·mixed는 돌리지 않음). 새 `explain.sql`로 uid 60·1·100, month_offset 0·-6 실행 계획 저장.
- [ ] **Step 4: 정리** — `docker rm -f -v petgyebu-perf-pg`, `git worktree remove ../petgyebu-before`. `docker ps -a`와 `docker volume ls`에 측정용 잔여물이 없는지 확인.
- [ ] **Step 5: 결과 문서 작성** — 2026-10-25 문서 형식(환경, EXPLAIN 표, k6 avg/p50/p95/p99 표 before/after, 해석). 기준 대비 2배를 넘는 값이 있으면 문서 맨 위에 적고 사용자에게 보고한다.
- [ ] **Step 6: Commit**

```bash
git add perf/results
git commit -m "perf: compare home and stats before and after moving monthly totals to the service"
```

---

### Task 4: 지출 요청 객체와 `ExpenseService` 규칙

**Files:**
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/common/RequestBodies.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/expense/ExpenseFields.java` (package-private 파서 모음)
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/expense/ExpenseRequest.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/expense/ExpensePatch.java`
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/expense/Expense.java` (`sameContentAs` 추가)
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/expense/ExpenseService.java`
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/expense/ExpensePatchTests.java`, `src/test/java/org/altair/petgyebu_backend_renewal/expense/ExpenseIdempotencyApiTests.java`

**Interfaces:**
- Produces (Task 5가 사용):
  - `RequestBodies.requireObject(JsonNode body)` — 객체가 아니면 `ApiException(400, "INVALID_REQUEST", "요청 본문을 읽을 수 없습니다.", null)`
  - `long RequestBodies.amount(JsonNode node)` — 정수·`canConvertToLong`·1 이상 `RequestBodies.MAX_AMOUNT`(= `100_000_000`) 이하, 아니면 `invalidRequest("amount", "1 이상 100000000 이하의 정수여야 합니다.")`
- Produces (지출 내부):
  - `record ExpenseRequest(long amount, LocalDate expenseDate, Category category, String memo)`, `static ExpenseRequest from(JsonNode body, LocalDate today)` — 본문 객체 → amount → expenseDate → category(생략·null이면 `OTHER`) → memo 순
  - `record ExpensePatch(...)`, `static ExpensePatch from(JsonNode body, LocalDate today)` — 보낸 필드만 amount → expenseDate → category → memo 순으로 파싱; `Expense applyTo(Expense current, OffsetDateTime now)` — 생략 필드는 현재 값 유지, `memo` 키가 있으면 정규화된 값(null 포함)으로
  - `ExpenseFields`: `expenseDate(JsonNode, LocalDate today)`(형식 + 2000-01-01 ~ today, 기존 메시지 그대로), `category(JsonNode)`, `memo(JsonNode)`(생략·null·공백 → null, NUL 거부, 100 코드 포인트), `UUID idempotencyKey(String)`(36자 표준 형식). 상수 `MIN_DATE`, `MEMO_MAX_CODE_POINTS`는 여기로 옮긴다.
  - `boolean Expense.sameContentAs(ExpenseRequest request)` — amount·expenseDate·category·memo(`Objects.equals`) 비교

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// ExpensePatchTests (단위)
@Test void omittedFieldsKeepCurrentValuesAndNullMemoClears() {
    Expense current = new Expense(1L, 7L, 3000, LocalDate.of(2026, 10, 12), Category.HEALTH, "병원", T0, T0, null);
    Expense kept = ExpensePatch.from(json("{}"), TODAY).applyTo(current, T1);
    assertThat(kept).isEqualTo(new Expense(1L, 7L, 3000, LocalDate.of(2026, 10, 12), Category.HEALTH, "병원", T0, T1, null));
    assertThat(ExpensePatch.from(json("{\"memo\":null}"), TODAY).applyTo(current, T1).memo()).isNull();
}

// ExpenseIdempotencyApiTests
@Test void blankMemoReplayEqualsOmittedMemo() throws Exception {
    String a = accessToken("a");
    String key = UUID.randomUUID().toString();
    create(a, "{\"amount\":1000,\"expenseDate\":\"2026-10-13\"}", key).andExpect(status().isCreated());
    create(a, "{\"amount\":1000,\"expenseDate\":\"2026-10-13\",\"memo\":\"   \"}", key).andExpect(status().isOk());
    assertThat(expenseCount()).isEqualTo(1);
}
```

(`T0`, `T1`, `TODAY`, `json(...)`은 테스트 안의 상수·헬퍼. `TODAY = 2026-10-13`.)

- [ ] **Step 2: 실행** — `./gradlew test --no-daemon --tests '*ExpensePatchTests' --tests '*ExpenseIdempotencyApiTests'`. Expected: `ExpensePatchTests` 컴파일 실패, `blankMemoReplayEqualsOmittedMemo`는 현재 코드에서도 통과(동작 고정용).

- [ ] **Step 3: 구현**
  - `ExpenseService.create`: `ExpenseRequest.from(body, seoulToday())` → 키가 있으면 `ExpenseFields.idempotencyKey` → 기존 행 조회 → `validateSameContent(existing, request)`(다르면 `idempotencyKeyReused`) → 저장. FK/UNIQUE 처리, `@Transactional` 없음 주석은 그대로.
  - `ExpenseService.update`: `RequestBodies.requireObject(body)` → `get(userId, id)`(404) → `ExpensePatch.from(body, seoulToday()).applyTo(current, now)` → 명시적 `expenses.update(...)`, 0건이면 404.
  - `list`: `Months.range` 사용, limit·커서 규칙 그대로. `Cursor`는 Service에 남긴다.
  - `ExpenseService`의 private 파서(`requireObject`, `amount`, `expenseDate`, `category`, `memo`, `idempotencyKey`)와 옮긴 상수를 삭제한다. `DEFAULT_LIMIT`, `MAX_LIMIT`, `SEOUL`은 남긴다.

- [ ] **Step 4: 전체 테스트 통과 확인** — `./gradlew test --no-daemon`. Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "refactor: move expense request parsing out of ExpenseService and name its rules"
```

---

### Task 5: 예산·펫 요청 객체와 Service 규칙

**Files:**
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/budget/BudgetRequest.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/user/PetRequest.java`
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/budget/BudgetService.java`
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/user/UserService.java` (`selectPet`만)

**Interfaces:**
- Consumes: Task 4의 `RequestBodies.requireObject`, `RequestBodies.amount`, `RequestBodies.MAX_AMOUNT`
- Produces:
  - `record BudgetRequest(long amount)`, `static BudgetRequest from(JsonNode body)` — 본문 객체 → amount
  - `record PetRequest(String petType)`, `static PetRequest from(JsonNode body)` — 본문 객체 → `petType`이 문자열인지(아니면 `invalidRequest("petType", "CAT 또는 DOG만 허용합니다.")`)

- [ ] **Step 1: 구현**
  - `BudgetService.put`: `Months.parsePastOrCurrent` → `validateCurrentMonth(month)`(기존 메시지 "현재 월만 설정할 수 있습니다.") → `BudgetRequest.from(body)` → upsert(FK → 401 유지). `BudgetService.MAX_AMOUNT`는 삭제하고 `RequestBodies.MAX_AMOUNT`를 쓴다.
  - `UserService.selectPet`: `PetRequest.from(body)` → `validatePetType(request.petType())`(`CAT`·`DOG` 집합, 같은 field·메시지) → `updatePetType`, 0건이면 401 → `me(userId)`.

- [ ] **Step 2: 테스트** — `./gradlew test --no-daemon --tests '*BudgetApiTests' --tests '*UserApiTests'`. Expected: PASS (Task 1의 `putChecksMonthBeforeBody` 포함)

- [ ] **Step 3: Commit**

```bash
git add src
git commit -m "refactor: parse budget and pet requests in request records and name service rules"
```

---

### Task 6: 탈퇴용 DELETE를 소유 Repository로

**Files:**
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/user/UserRepository.java` (`deleteExpensesOf`, `deleteMonthlyBudgetsOf` 삭제)
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/expense/ExpenseRepository.java`
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/budget/MonthlyBudgetRepository.java`
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/user/UserService.java` (`withdraw`, 생성자)

**Interfaces:**
- Produces:
  - `@Modifying @Query("DELETE FROM expenses WHERE user_id = :userId") int ExpenseRepository.deleteAllOfUser(long userId)`
  - `@Modifying @Query("DELETE FROM monthly_budgets WHERE user_id = :userId") int MonthlyBudgetRepository.deleteAllOfUser(long userId)`

- [ ] **Step 1: 구현** — `UserService`가 `ExpenseRepository`, `MonthlyBudgetRepository`를 주입받아 같은 트랜잭션 람다 안에서 `expenses.deleteAllOfUser` → `budgets.deleteAllOfUser` → `users.deleteUser` 순으로 호출한다. 재시도 루프·`WITHDRAW_ATTEMPTS = 3`·주석 유지. `UserRepository`의 "탈퇴 전용" 주석은 `deleteUser`에만 남긴다.
- [ ] **Step 2: 테스트** — `./gradlew test --no-daemon --tests '*UserApiTests'`. Expected: PASS (롤백·FK 재시도·3회 포기·비FK 미재시도 테스트 포함)
- [ ] **Step 3: Commit**

```bash
git add src
git commit -m "refactor: move withdrawal deletes to the repositories that own the tables"
```

---

### Task 7: 문서 갱신과 최종 검증

**Files:**
- Modify: `AGENTS.md:18`
- Modify: `docs/09-db-design.md:128`
- Modify: `docs/05-infra-stack.md:14,26,49`
- Modify: `docs/10-task-backlog.md` (M-008·M-009 기록 아래 후속 기록 한 줄씩)
- Modify: `perf/README.md` (`explain.sql` 설명이 바뀌면)

- [ ] **Step 1: 문서 수정**
  - `AGENTS.md`: "월 통계는 명시적 쿼리로 계산한다" → "월 지출 행은 명시적 쿼리로 조회하고 합계·카테고리 집계는 Service의 도메인 객체에서 계산한다".
  - `09` 4절: `COALESCE(SUM)`·`COUNT(*)`·`GROUP BY` 문장 → 같은 범위의 `(category, amount)` 행을 조회해 `MonthlySpending`이 총액·건수·카테고리별 합계를 계산, 0건이면 0원·0건·빈 배열.
  - `05`: "명시적 월 집계 쿼리"·"사용자·월 집계는 명시적 쿼리로 계산"·"월 `SUM`·`GROUP BY` 집계"를 행 조회 + 애플리케이션 집계로. 갱신일 2026-10-27.
  - `10`: M-008·M-009 기록은 고치지 않고 "> 2026-10-27 후속: 월 합계·카테고리 집계를 `MonthlySpending`으로 옮김(`ExpenseRepository.findAmounts` 행 조회), 통계 트랜잭션은 READ COMMITTED 읽기 전용. 측정: `perf/results/2026-10-27-monthly-spending.md`" 형식으로 덧붙인다.
- [ ] **Step 2: 남은 참조 확인** — `grep -rn "sumAmount\|sumByCategory\|deleteExpensesOf\|deleteMonthlyBudgetsOf" src docs AGENTS.md perf/explain.sql perf/README.md` 결과가 과거 기록(`docs/10` M-008·M-009, `docs/04`, `perf/results/2026-10-25*`) 외에 없어야 한다.
- [ ] **Step 3: 전체 검증** — `./gradlew cleanTest test --no-daemon`. Expected: PASS, 실패 0
- [ ] **Step 4: Commit**

```bash
git add AGENTS.md docs perf/README.md
git commit -m "docs: record that monthly totals are computed in the service layer"
```
