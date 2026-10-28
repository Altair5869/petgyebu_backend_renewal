# 카테고리별 목표 금액·카테고리 지출 목록 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사용자·월·카테고리별 목표 금액(합 ≤ 그 달 예산)을 설정·조회·삭제하는 API와, 월 지출 목록의 `category` 필터를 추가한다.

**Architecture:** 새 테이블 `category_goals`(V3)와 새 패키지 `goal`을 둔다.
- DB는 행 조회, upsert, 예산 행 잠금(`FOR UPDATE`)만 맡는다.
- 목표 합과 사용 현황은 도메인 객체 `CategoryGoals`가 계산한다.
- Service는 이름 붙은 규칙(`requireMonthBudget`, `validateWithinBudget`)만 갖는다.
- 지출 목록 필터는 기존 커서 목록에 카테고리 조건만 더한다.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring Data JDBC, PostgreSQL 16, Flyway, Jackson 3(`tools.jackson.databind.JsonNode`), Testcontainers, MockMvc, `MutableClock`.

**Spec:** `docs/superpowers/specs/2026-10-28-category-goals-design.md`

## Global Constraints

- 패키지 루트: `org.altair.petgyebu_backend_renewal`. Lombok·JPA·QueryDSL은 쓰지 않는다. 코드는 주변 코드의 주석 밀도와 관용구(한국어 짧은 주석)에 맞춘다.
- `{month}`는 `Months.parsePastOrCurrent(text, clock)`로 해석한다. 2000-01부터 서울 기준 현재 월까지만 받고, 그 밖은 400 `INVALID_REQUEST`(`month`)다.
- 금액은 `RequestBodies.requireObject` 뒤 `RequestBodies.amount(body.get("amount"))`로 검증한다(1 이상 100,000,000 이하 정수, 필드 `amount`).
- 카테고리 문자열은 대소문자를 구분해 `Category`의 7개 이름과 정확히 일치해야 한다. 틀리면 400 `INVALID_REQUEST`(`category`)이고 메시지는 "허용되지 않는 카테고리입니다."다.
- DB `CHECK`과 `ON DELETE CASCADE`는 두지 않는다. FK와 `UNIQUE(user_id, goal_month, category)`는 둔다.
- 오류 코드:
  - 예산 없음: 409 `BUDGET_REQUIRED`, 메시지 "이 달 예산을 먼저 설정해야 합니다."
  - 한도 초과: 409 `CATEGORY_GOALS_EXCEED_BUDGET`, 메시지 "카테고리 목표 합계가 월 예산을 넘을 수 없습니다."
- 사용률은 `BudgetUsage(goalAmount, spentAmount).usagePercent()`로 계산한다(소수 첫째 자리 버림, 100 초과 가능).
- 탈퇴 삭제 순서는 지출 → 카테고리 목표 → 월 예산 → 사용자이며, 한 트랜잭션에서 지운다. FK 재시도는 최대 3회다.
- 홈·통계·인사이트 응답은 바꾸지 않는다. 펫 상태는 전체 예산 기준 그대로다.
- 테스트 기준 시각: `TestAuthConfig.START` = 2026-10-13T00:00Z(서울 10-13 09:00, 현재 월 2026-10).
- 완료 전 `./gradlew test --no-daemon` 전체 통과.

## Review Focus

1. 예산을 낮춰 이미 초과된 달에서 목표를 줄이는 PUT을 보내면, 결과 합이 여전히 예산을 넘으므로 409여야 한다(스펙의 단일 규칙). → Task 3 `putIsRejectedWhileGoalsStillExceedLoweredBudget`
2. 같은 카테고리를 다시 PUT할 때는 기존 그 카테고리 값을 빼고 계산해야 한다. 예산 100, 목표 CAFE 60일 때 CAFE를 100으로 바꾸면 허용된다. → Task 2 `totalWithReplacesSameCategory`, Task 3 `replacingSameCategoryCountsOnlyNewAmount`
3. 다른 카테고리 목표 PUT이 동시에 와도 합이 예산을 넘지 않아야 한다. → Task 3 `concurrentPutsNeverExceedBudget`
4. 목표가 있는 사용자의 탈퇴가 FK로 실패하면 안 된다. 모든 테스트의 `TRUNCATE`에 새 테이블이 포함돼야 한다. → Task 1
5. 카테고리 필터와 커서를 함께 써도 다음 페이지가 그 카테고리만 이어서 돌려줘야 한다. → Task 4 `categoryFilterPagesWithCursor`

---

### Task 1: `category_goals` 테이블·엔티티·리포지토리와 탈퇴 삭제

**Files:**
- Create: `src/main/resources/db/migration/V3__category_goals.sql`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/goal/CategoryGoal.java`
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/goal/CategoryGoalRepository.java`
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/user/UserService.java` (`withdraw`와 그 위 주석)
- Modify: 테스트 11개의 `TRUNCATE expenses, monthly_budgets, users RESTART IDENTITY` → `TRUNCATE expenses, category_goals, monthly_budgets, users RESTART IDENTITY`. 대상은 `grep -rln "TRUNCATE expenses" src/test`로 찾는다.
- Modify: `src/test/java/org/altair/petgyebu_backend_renewal/FlywayBaselineSchemaTests.java`
- Modify: `src/test/java/org/altair/petgyebu_backend_renewal/user/UserApiTests.java`

**Interfaces:**
- Produces:
  - `record CategoryGoal(@Id Long id, long userId, LocalDate goalMonth, Category category, long amount, OffsetDateTime createdAt, OffsetDateTime updatedAt)`, `@Table("category_goals")`, public.
  - `public interface CategoryGoalRepository extends Repository<CategoryGoal, Long>`:
    - `List<CategoryGoal> findAll(long userId, LocalDate goalMonth)`: `SELECT *` where user_id and goal_month.
    - `CategoryGoal upsert(long userId, LocalDate goalMonth, String category, long amount, OffsetDateTime now)`: `INSERT … ON CONFLICT (user_id, goal_month, category) DO UPDATE SET amount = EXCLUDED.amount, updated_at = EXCLUDED.updated_at RETURNING *`. `MonthlyBudgetRepository.upsert`와 같은 모양이다.
    - `@Modifying int delete(long userId, LocalDate goalMonth, String category)`.
    - `@Modifying int deleteAllOfUser(long userId)`: 탈퇴 전용 주석을 단다.

- [ ] **Step 1: 실패하는 테스트 작성**
  - `FlywayBaselineSchemaTests`:
    - `createsOnlyTheThreeMvpTables` → 이름을 `createsOnlyTheDesignedTables`로 바꾸고 `"users", "expenses", "monthly_budgets", "category_goals"`를 기대한다.
    - `columnsMatchDesignedTypesAndNullability`에 `category_goals`를 추가한다: `id bigint NO`, `user_id bigint NO`, `goal_month date NO`, `category character varying(20) NO`, `amount bigint NO`, `created_at`/`updated_at timestamp with time zone NO`.
    - `hasNoCheckConstraints`의 `relname IN`에 `'category_goals'`를 추가한다.
    - 새 테스트 `rejectsSecondGoalForSameUserMonthCategory`: 같은 (user, `2026-10-01`, `CAFE`)를 두 번 INSERT하면 `DataIntegrityViolationException`이고, 다른 카테고리는 허용한다.
    - `doesNotCascadeUserDeletion`에 목표만 가진 사용자의 삭제가 `DataIntegrityViolationException`인지 추가한다.
  - `UserApiTests`:
    - `seedRows`가 사용자당 목표 1행(`2026-10-01`, `CAFE`, 1000)도 넣는다.
    - `counts(userId)`에 목표 수를 넣는다. 기대값 예: 탈퇴한 사용자 `0, 0, 0, 0`, 남은 사용자는 기존 값에 목표 1을 더한다. 기존 `containsExactly`도 새 순서에 맞춘다.
- [ ] **Step 2: 실패 확인.** `./gradlew test --no-daemon --tests '*FlywayBaselineSchemaTests' --tests '*UserApiTests'`를 실행한다. 기대 결과: FAIL(테이블 없음).
- [ ] **Step 3: 구현**
  - V3: 스펙 3절의 컬럼과 `CONSTRAINT uq_category_goals_user_month_category UNIQUE (user_id, goal_month, category)`.
  - 엔티티와 리포지토리.
  - `UserService`: 생성자에 `CategoryGoalRepository goals`를 받는다. `withdraw`에서 `expenses.deleteAllOfUser` 다음, `budgets.deleteAllOfUser` 앞에 `goals.deleteAllOfUser(userId)`를 넣는다. 주석을 "지출 → 카테고리 목표 → 월 예산 → 사용자 순"으로 고친다.
  - TRUNCATE 11곳을 바꾼다.
- [ ] **Step 4: 통과 확인.** `./gradlew test --no-daemon`을 실행한다. 기대 결과: 전체 PASS.
- [ ] **Step 5: 커밋.** `feat: add category_goals table and delete goals on withdrawal`

### Task 2: 도메인 객체 `CategoryGoals`

**Files:**
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/goal/CategoryGoals.java`
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/goal/CategoryGoalsTests.java` (DB 없는 순수 단위 테스트)

**Interfaces:**
- Consumes: `CategoryGoal`(Task 1), `MonthlySpending.of(List<ExpenseAmount>)`, `MonthlySpending.categories()`가 돌려주는 `CategoryTotal(category, spentAmount, expenseCount)`, `BudgetUsage(budgetAmount, spentAmount).usagePercent()`.
- Produces (`public final class CategoryGoals`):
  - `static CategoryGoals of(List<CategoryGoal> goals)`
  - `long total()`: 목표 금액의 합. 목표가 없으면 0이다.
  - `long totalWith(Category category, long amount)`: 그 카테고리를 `amount`로 바꿨을 때의 합. 기존 값은 빼고 계산한다.
  - `boolean exceeds(Long budgetAmount)`: `budgetAmount != null && total() > budgetAmount`.
  - `List<Progress> progress(MonthlySpending spending)`: 목표가 있는 카테고리만 `Category` 선언 순서로 돌려준다.
  - `public record Progress(Category category, long goalAmount, long spentAmount, long remainingAmount, BigDecimal usagePercent)`. `remainingAmount = goalAmount - spentAmount`다.

- [ ] **Step 1: 실패하는 테스트 작성** (`CategoryGoalsTests`, 헬퍼 `goal(Category, long)`와 `spending(Object... categoryAmountPairs)`)
  - `progressListsOnlyGoalCategoriesInDeclarationOrder`: 목표 OTHER 1000, FOOD 2000, 지출 CAFE 500 → `[FOOD, OTHER]`. 둘 다 spent 0, remaining = 목표, usagePercent `0.0`.
  - `overspentGoalHasNegativeRemainingAndOverHundredPercent`: CAFE 목표 10000, 지출 12000+500 → spent 12500, remaining -2500, usagePercent `125.0`.
  - `usagePercentIsTruncated`: 목표 10000, 지출 4999 → `49.9`.
  - `totalAndTotalWith`: 목표 FOOD 3000, CAFE 2000 → `total()` 5000. `totalWith(CAFE, 500)` 3500(교체). `totalWith(HEALTH, 1000)` 6000(추가).
  - `totalWithReplacesSameCategory`: 목표 CAFE 60 → `totalWith(CAFE, 100)` 100.
  - `exceedsOnlyWhenBudgetPresentAndBelowTotal`: total 5000이고 `exceeds(5000L)`는 false, `exceeds(4999L)`는 true, `exceeds(null)`은 false. 목표가 없으면 `total()` 0, `exceeds(1L)`은 false.
- [ ] **Step 2: 실패 확인.** `./gradlew test --no-daemon --tests '*CategoryGoalsTests'`를 실행한다. 기대 결과: 컴파일 FAIL.
- [ ] **Step 3: 구현.** `EnumMap<Category, Long>`으로 목표를 담는다. 사용액은 `spending.categories()`를 카테고리별로 찾아 쓰고, 없으면 0이다.
- [ ] **Step 4: 통과 확인.** 같은 명령을 실행한다. 기대 결과: PASS.
- [ ] **Step 5: 커밋.** `feat: compute category goal totals and progress in CategoryGoals`

### Task 3: 목표 API (GET·PUT·DELETE)

**Files:**
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/expense/Category.java`: `public static Category parse(String text)` 추가.
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/common/ApiException.java`: `monthBudgetRequired()`, `categoryGoalsExceedBudget()` 추가. 메시지는 Global Constraints를 따른다.
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/budget/MonthlyBudgetRepository.java`: `Optional<MonthlyBudget> findForUpdate(long userId, LocalDate budgetMonth)` 추가. `find`와 같은 쿼리에 `FOR UPDATE`를 붙이고 "목표 저장을 같은 사용자·월에서 하나씩 처리하려고 잠근다" 주석을 단다.
- Create: `goal/CategoryGoalRequest.java`: `record CategoryGoalRequest(long amount)`, `static from(JsonNode body)`. `BudgetRequest`와 같은 모양이다.
- Create: `goal/CategoryGoalService.java`
- Create: `goal/CategoryGoalController.java`: `@RequestMapping("/api/v1/months/{month}/category-goals")`.
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/goal/CategoryGoalApiTests.java`. 셋업은 `BudgetApiTests`와 같다(Testcontainers, `TestAuthConfig`, `MutableClock`, `accessToken` 헬퍼).

**Interfaces:**
- Consumes: Task 1의 리포지토리, Task 2의 `CategoryGoals`, `ExpenseRepository.findAmounts(userId, from, to)`, `MonthlyBudgetRepository.find`, `Months.range`, `DbErrors.isForeignKeyViolation`.
- Produces:
  - `CategoryGoalService.get(long userId, String month)`: `@Transactional(readOnly = true)`. 반환은 `record MonthGoals(YearMonth month, Long budgetAmount, CategoryGoals goals, List<CategoryGoals.Progress> progress)`.
  - `CategoryGoal put(long userId, String month, String category, JsonNode body)`: `@Transactional`.
  - `void delete(long userId, String month, String category)`: `@Transactional`.
  - 응답 record(컨트롤러 안):
    - GET: `{month, budgetAmount, goalTotalAmount, goalsExceedBudget, goals:[{category, goalAmount, spentAmount, remainingAmount, usagePercent}]}`
    - PUT: 200 `{month, category, amount}`
    - DELETE: 204
  - `Category.parse(String)`은 Task 4도 쓴다.

**PUT 순서**(Service 본문이 이 순서로 읽혀야 한다):
1. `Months.parsePastOrCurrent`
2. `Category.parse`
3. `CategoryGoalRequest.from(body)`
4. `requireMonthBudget`: `budgets.findForUpdate(…)`, 없으면 `monthBudgetRequired()`
5. `validateWithinBudget`: `CategoryGoals.of(goals.findAll(…)).totalWith(category, amount) > budget.amount()`이면 `categoryGoalsExceedBudget()`
6. `goals.upsert(…, OffsetDateTime.now(clock))`. FK 위반은 `ApiException.unauthorized()`로 바꾼다(`BudgetService.put`과 같은 모양).

**DELETE 순서:** month → category → `goals.delete`. 예산은 확인하지 않는다.

- [ ] **Step 1: 실패하는 테스트 작성** (`CategoryGoalApiTests`, 사용자 a = id 1, b = id 2. 예산은 `PUT /months/{m}/budget`이나 jdbc INSERT로 넣는다)
  - `getReturnsExactShape`:
    - 준비: 예산 500000, 목표 CAFE 50000, FOOD 100000, 지출 CAFE 32000.
    - `JSONAssert` STRICT로 확인한다(`InsightApiTests`처럼). 기대값: `{"month":"2026-10","budgetAmount":500000,"goalTotalAmount":150000,"goalsExceedBudget":false,"goals":[{"category":"FOOD","goalAmount":100000,"spentAmount":0,"remainingAmount":100000,"usagePercent":0.0},{"category":"CAFE","goalAmount":50000,"spentAmount":32000,"remainingAmount":18000,"usagePercent":64.0}]}`
  - `getWithoutBudgetOrGoals`: 예산·목표·펫 없이 200. `budgetAmount` null, `goalTotalAmount` 0, `goalsExceedBudget` false, `goals` `[]`.
  - `putCreatesThenReplacesInOneRow`:
    - PUT CAFE 30000 → 200 `{"month":"2026-10","category":"CAFE","amount":30000}`.
    - 5분 뒤 40000으로 PUT하면 같은 id다. `created_at`은 START 그대로이고 `updated_at`은 START+5m이다.
  - `goalsMayEqualBudgetButNotExceedIt`:
    - 준비: 예산 100000, FOOD 60000.
    - CAFE 40000은 200이다.
    - HEALTH 1은 409 `CATEGORY_GOALS_EXCEED_BUDGET`이고 행 수는 2 그대로다.
  - `replacingSameCategoryCountsOnlyNewAmount`: 예산 100, CAFE 60. CAFE를 100으로 PUT하면 200이다.
  - `putWithoutMonthBudgetIsBudgetRequired`:
    - 10월 예산이 없으면 409 `BUDGET_REQUIRED`이고 행은 0건이다.
    - 9월 예산만 있어도 10월 PUT은 409다.
  - `putIsRejectedWhileGoalsStillExceedLoweredBudget`:
    - 준비: 예산 100000, FOOD 60000, CAFE 40000. 예산을 50000으로 PUT하면 200이다.
    - GET 결과: `goalsExceedBudget` true, `goalTotalAmount` 100000.
    - CAFE 30000 PUT(결과 합 90000 > 50000)은 409이고 CAFE는 40000 그대로다.
    - FOOD를 DELETE한 뒤 CAFE 30000 PUT은 200이다.
  - `deleteIsIdempotent`:
    - 목표가 있으면 204, 다시 지워도 204다. 예산이 없는 달(9월)의 DELETE도 204다.
    - 같은 달 다른 카테고리 목표는 남는다.
  - `pastMonthAllowedFutureRejected`:
    - 9월 예산 200000이 있으면 9월 CAFE PUT은 200이다.
    - 11월 PUT·GET·DELETE는 400 `month`다.
  - `validationOrderIsMonthCategoryBodyThenBudget`: 예산 없는 상태에서 확인한다.
    - `2026-11` + `cafe` + `[]` → `month`
    - `2026-10` + `cafe` + `[]` → `category`
    - `2026-10` + `CAFE` + `[]` → 400 `INVALID_REQUEST`(본문)
    - `2026-10` + `CAFE` + `{"amount":0}` → `amount`
    - `2026-10` + `CAFE` + `{"amount":1000}` → 409 `BUDGET_REQUIRED`
    - DELETE `2026-10/cafe` → `category`
  - `goalsAreIsolatedPerUser`:
    - a의 목표는 b의 GET에 보이지 않는다.
    - b의 DELETE는 a의 행을 지우지 않는다.
    - b의 PUT은 b의 예산 기준으로 검사한다.
  - `endpointsRequireAuthentication`: GET·PUT·DELETE 모두 401이다.
  - `concurrentPutsNeverExceedBudget`:
    - 준비: 예산 100000. 7개 카테고리에 각각 30000씩 서비스 `put`을 10개 스레드 풀에서 동시에 호출한다(`BudgetApiTests.concurrentPuts…`와 같은 래치 방식).
    - 각 결과는 성공이거나 `ApiException` 409 `CATEGORY_GOALS_EXCEED_BUDGET`다.
    - 끝난 뒤 목표 합 ≤ 100000이고 성공 수 = 행 수 = 3이다.
  - FK 위반 → 401 경로는 테스트하지 않는다. 예산 행이 남아 있으면 사용자 행을 지울 수 없어 재현할 수 없다. 코드는 `BudgetService.put`과 같은 모양으로 둔다.
- [ ] **Step 2: 실패 확인.** `./gradlew test --no-daemon --tests '*CategoryGoalApiTests'`를 실행한다. 기대 결과: FAIL.
- [ ] **Step 3: 구현.** 위 Interfaces와 순서를 따른다. GET은 `budgets.find`, `goals.findAll`, `MonthlySpending.of(expenses.findAmounts(range))`를 한 읽기 트랜잭션에서 조회한다.
- [ ] **Step 4: 통과 확인.** 같은 명령을 실행한 뒤 `./gradlew test --no-daemon` 전체를 실행한다. 기대 결과: PASS.
- [ ] **Step 5: 커밋.** `feat: add category goal API capped by the month budget`

### Task 4: 지출 목록 `category` 필터

**Files:**
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/expense/ExpenseRepository.java`: `findFirstPageInCategory`, `findPageAfterInCategory` 추가. 기존 쿼리에 `AND category = :category`를 더한 것이고, 인자 `String category`는 기존 인자 뒤, `limit` 앞에 둔다.
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/expense/ExpenseService.java`: `list(long userId, String monthText, int limit, String cursor, String category)`. `category`가 null이면 기존 메서드를 쓴다.
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/expense/ExpenseController.java`: `@RequestParam(required = false) String category`.
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/expense/ExpenseApiTests.java`

**Interfaces:**
- Consumes: `Category.parse(String)`(Task 3).
- 검증 순서: month → limit → category → cursor. 커서 형식은 바뀌지 않는다.

- [ ] **Step 1: 실패하는 테스트 작성** (`ExpenseApiTests`에 추가)
  - `categoryFilterReturnsOnlyThatCategoryInListOrder`:
    - 준비: 10월에 CAFE 3건(10-05, 10-10, 10-10 두 번째)과 FOOD 2건, 9월에 CAFE 1건.
    - `?category=CAFE` → CAFE 3건만, 날짜 내림차순, 같은 날짜는 id 내림차순이며 `nextCursor` null이다.
  - `categoryFilterPagesWithCursor`:
    - 준비: CAFE 3건과 FOOD 3건을 섞는다.
    - `?category=CAFE&limit=2` → CAFE 2건과 커서.
    - 커서와 `category=CAFE`로 다시 요청 → 남은 CAFE 1건, `nextCursor` null.
  - `categoryFilterExcludesOtherUsers`: b의 CAFE는 a의 필터 결과에 없다.
  - `invalidCategoryFilterIsRejected`:
    - `cafe`, `COFFEE`, 빈 문자열 → 400 `category`.
    - `2026-11` + `cafe` → `month`.
    - `limit=0&category=cafe` → `limit`.
  - 기존 목록 테스트는 바꾸지 않는다. 필터가 없을 때의 동작은 기존 테스트가 고정한다.
- [ ] **Step 2: 실패 확인.** `./gradlew test --no-daemon --tests '*ExpenseApiTests'`를 실행한다. 기대 결과: FAIL.
- [ ] **Step 3: 구현.**
- [ ] **Step 4: 통과 확인.** 전체 `./gradlew test --no-daemon`을 실행한다. 기대 결과: PASS.
- [ ] **Step 5: 커밋.** `feat: filter the monthly expense list by category`

### Task 5: 문서와 검증 기록

**Files:**
- Modify: `AGENTS.md`(MVP 범위 2번째 줄): "3개 논리 테이블(`users`, `expenses`, `monthly_budgets`)" → "4개 논리 테이블(`users`, `expenses`, `monthly_budgets`, `category_goals`)"
- Modify: `docs/00-renewal-development-guide.md`
  - 테이블 표·제약에 `category_goals` 추가.
  - API 표에 17·18·19번 동작 추가, 동작 수·고유 경로 수 문구 갱신(110행 "16개 동작, 12개 고유 경로" → "19개 동작, 14개 고유 경로").
  - 11번 목록에 선택 `category` 추가.
  - 목표 규칙 절: 합 ≤ 예산, 예산 낮추기 허용, `goalsExceedBudget`, 삭제 204, 단일 규칙.
  - 오류 코드 `CATEGORY_GOALS_EXCEED_BUDGET`.
  - 탈퇴 순서.
- Modify: `docs/02-requirements-features.md`: 새 기능 ID. 기존 마지막이 `F-M07`이므로 `F-M08 카테고리 목표`로 하고, 카테고리 필터는 `F-M03` 지출 목록 항목에 한 줄 추가한다.
- Modify: `docs/08-feature-implementation-map.md`: 기능·API 행 추가. 6행·합계 행(81행)을 19개 동작·14개 고유 경로·4개 논리 테이블로 바꾼다.
- Modify: `docs/05-infra-stack.md` 51행: "세 테이블" → "네 테이블".
- Modify: `docs/09-db-design.md` 140행: 기준선 설명은 그대로 두고 "`category_goals`는 `V3`에서 추가한다"를 덧붙인다.
- Modify: `docs/09-db-design.md`: 테이블·UNIQUE·FK·잠금·삭제 순서.
- Modify: `docs/10-task-backlog.md`: 범위 문구를 `M-013`, `APP-008`로 갱신하고 "후속 — 카테고리 목표" 절을 추가한다.
  - `M-013` 백엔드: [x]와 검증 기록. 테스트 개수와 전체 개수는 실제 실행 결과로 적는다.
  - `APP-008` 앱 화면(목표 설정·현황·초과 안내, 카테고리별 지출 목록): [ ].
- Modify: `docs/06-sprint-plan.md` 6행 작업 ID 문구에 `M-013`·`APP-008` 추가.
- Modify: `perf/explain.sql`: 목표 조회(`category_goals` user·month)와 카테고리 필터 첫 페이지 블록. 기존 블록 형식을 따른다.

- [ ] **Step 1:** 위 파일을 고친다. 고친 뒤 "3개 논리 테이블", "16개 동작", "세 테이블"이 남았는지 `grep -rn`으로 확인한다. `docs/04`, `docs/superpowers`, 이력 기록은 그대로 둔다.
- [ ] **Step 2:** `./gradlew cleanTest test --no-daemon`을 실행해 개수를 기록에 반영한다. 기대 결과: PASS.
- [ ] **Step 3: 커밋.** `docs: document category goals and the expense category filter`
