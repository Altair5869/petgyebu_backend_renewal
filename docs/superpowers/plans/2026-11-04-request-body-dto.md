# 요청 본문 DTO 전환 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 본문을 받는 다섯 API(지출 생성·수정, 예산 PUT, 목표 PUT, 펫 선택)를 `JsonNode` 직접 파싱에서 DTO record + `@Valid`로 바꾼다.

**Architecture:** 기존 `*Request`/`ExpensePatch` record를 그대로 DTO로 쓰고 Bean Validation 제약을 단다. PATCH의 생략/명시적 null은 `JsonNullable<T>`로 구분한다. 앱 전역 `JsonMapper`에 엄격한 coercion을 넣고, JSON 타입 오류는 `HttpMessageNotReadableException` 처리기가 필드 이름 + 일반 문구로 바꾼다.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Jackson 3(`tools.jackson`), Hibernate Validator(`spring-boot-starter-validation`, 이미 있음), `org.openapitools:jackson-databind-nullable:0.2.12`, Testcontainers PostgreSQL.

**Spec:** `docs/superpowers/specs/2026-11-04-request-body-dto-design.md`

## Global Constraints

- 저장소 `AGENTS.md`와 `.agents/skills/karpathy-guidelines/SKILL.md`를 따른다. 구현은 backend-engineer 역할, 검토는 qa-engineer 역할.
- 필드 이름과 JSON 타입이 맞는 값의 오류 문구는 바꾸지 않는다. 문구(그대로 복사):
  - amount: `1 이상 100000000 이하의 정수여야 합니다.`
  - expenseDate: `YYYY-MM-DD 형식이어야 합니다.` / `2000-01-01부터 오늘까지만 허용합니다.`
  - category: `허용되지 않는 카테고리입니다.`
  - memo: `허용되지 않는 문자가 있습니다.` / `최대 100자입니다.`
  - petType: `CAT 또는 DOG만 허용합니다.`
- 필드 오류 응답: `400`, `code` `INVALID_REQUEST`, `message` `요청 값이 올바르지 않습니다.`, `fields[]`에 틀린 필드 **모두**(순서 보장 없음, 한 필드가 여러 번 나와도 됨).
- JSON 타입이 틀린 값: 그 필드 하나 + 문구 `형식이 올바르지 않습니다.` (code·message는 위와 같음).
- 본문을 읽을 수 없음(문법 오류, 본문 없음, 객체가 아닌 본문 `[]`·`"x"`·`null`): `400 INVALID_REQUEST`, `요청 본문을 읽을 수 없습니다.`, `fields` 없음.
- 거부해야 할 값: amount에 `"5000"`, `1.5`, `1.0`, `1e3`, `true`, `""`, `9223372036854775808`; 문자열 필드에 JSON 숫자·불리언(예: `{"memo":1234}`). 숫자가 든 문자열 메모(`"1234"`)는 허용.
- 알 수 없는 필드는 지금처럼 무시한다.
- 본문 검증은 경로 변수 검증(`month`, `category`)·지출 존재 404·예산 존재 409보다 먼저다. `POST /expenses`의 본문 → `Idempotency-Key` 형식 → 재요청 → 월 상한 순서는 그대로다.
- `./gradlew test --no-daemon` 통과. 비밀값·개인 데이터를 넣지 않는다.

## Review Focus

1. `JsonNullable<…>` 필드의 위반이 `fields[].field`에 `memo`·`amount`처럼 **필드 이름 그대로** 나오는지(컨테이너 요소 경로 `memo.<…>`로 나오면 앱이 매칭을 못 한다) → Task 4 테스트.
2. 객체가 아닌 본문(`[]`, `"x"`, `null`, 빈 본문, 깨진 JSON)이 500이나 `fields` 있는 응답이 되지 않는지 → Task 1·2·4 테스트.
3. 경계 밖 정수(2^63)·지수 표기(`1e3`)·빈 문자열이 범위 문구가 아니라 타입 오류 문구로, 500 없이 거부되는지 → Task 2 테스트.
4. 서울 자정 경계의 `expenseDate`(UTC 전날인 서울 00:30, 서울 기준 내일)가 커스텀 제약에서도 기존과 같게 판정되는지 → Task 3(기존 `seoulTomorrowIsFutureJustBeforeSeoulMidnight` 등 유지).
5. 멱등 재요청 비교가 정규화한 값(공백 메모 → `null`, category 생략 → `OTHER`)으로 이뤄져, 같은 요청을 다시 보내면 여전히 200인지 → Task 3 테스트.

---

### Task 1: 공통 바인딩 기반 (의존성, 엄격 coercion, 타입 오류 처리기)

**Files:**
- Modify: `build.gradle` (`implementation 'org.openapitools:jackson-databind-nullable:0.2.12'`)
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/common/JacksonConfig.java`
- Modify: `src/main/java/org/altair/petgyebu_backend_renewal/common/ApiExceptionHandler.java` (`handle(HttpMessageNotReadableException)`)
- Test: `src/test/java/org/altair/petgyebu_backend_renewal/common/RequestBodyBindingApiTests.java`
- Docs: `docs/05-infra-stack.md`(의존성), `docs/00-renewal-development-guide.md`(공통 오류 규칙: 타입 오류 문구, 로그인 `credential`이 문자열이 아니면 400)

**Interfaces:**
- Produces: `JacksonConfig#strictCoercion(): JsonMapperBuilderCustomizer` 빈. 정수(`LogicalType.Integer`)는 String·Float·Boolean·EmptyString 입력을, 문자열(`LogicalType.Textual`)은 Integer·Float·Boolean 입력을 `CoercionAction.Fail`로 둔다. `JsonNullable` 모듈은 Boot 자동 등록에 맡긴다.
- Produces: 처리기 규칙 — 원인 사슬에 경로가 비어 있지 않은 `tools.jackson.databind.DatabindException`(예: `MismatchedInputException`, `InvalidFormatException`)이 있으면 경로 첫 속성 이름으로 `ApiError("INVALID_REQUEST", "요청 값이 올바르지 않습니다.", [FieldError(name, "형식이 올바르지 않습니다.")])`, 아니면 지금 응답.

- [ ] **Step 1: 실패 테스트 작성** — 로그인 DTO(이미 DTO)로 검증한다. `FakeProviderServer`/`TestAuthConfig` 방식은 기존 `AuthApiTests`를 따른다.
  - `credentialOfWrongJsonTypeIsFieldTypeError`: `POST /api/v1/auth/login/kakao` 본문 `{"credential":123}` → 400, `$.code`=`INVALID_REQUEST`, `$.fields[0].field`=`credential`, `$.fields[0].message`=`형식이 올바르지 않습니다.`
  - `unreadableBodiesKeepGenericError`: 같은 경로에 `[]`, `"x"`, `{`, 빈 본문 → 각각 400, `$.message`=`요청 본문을 읽을 수 없습니다.`, `$.fields` 없음.
  - `jsonNullableModuleIsRegistered`: 컨텍스트의 `JsonMapper`로 `record R(JsonNullable<String> m)`에 `{}` → `m().isPresent()` false, `{"m":null}` → present 이고 `get()` null.
  - `strictCoercionRejectsLooseNumbers`: 컨텍스트 `JsonMapper`로 `record N(Long a)`에 `"5000"`, `1.5`, `1.0`, `1e3`, `true`, `""` → 모두 `DatabindException`; `record S(String s)`에 `1234`, `true` → 예외; `{"s":"1234"}` → `"1234"`.
- [ ] **Step 2: 실행해 실패 확인** — `./gradlew test --no-daemon --tests '*RequestBodyBindingApiTests'` → credential·coercion 테스트 FAIL(현재 숫자를 문자열로 받아들여 401 등).
- [ ] **Step 3: 의존성·`JacksonConfig`·처리기 구현.**
- [ ] **Step 4: 실행해 통과 확인** — 같은 명령 PASS, 이어서 `./gradlew test --no-daemon` 전체 PASS(다른 본문 API는 아직 JsonNode라 영향 없어야 함).
- [ ] **Step 5: 문서 갱신 후 커밋** — `git commit -m "feat: reject loosely coerced JSON values and report JSON type errors per field"`

### Task 2: 예산·목표·펫 본문 DTO

**Files:**
- Create: `src/main/java/org/altair/petgyebu_backend_renewal/common/Amount.java` (제약 애너테이션)
- Modify: `budget/BudgetRequest.java`, `budget/BudgetController.java`, `budget/BudgetService.java`
- Modify: `goal/CategoryGoalRequest.java`, `goal/CategoryGoalController.java`, `goal/CategoryGoalService.java`
- Modify: `user/PetRequest.java`, `user/UserController.java`, `user/UserService.java`
- Test: `budget/BudgetApiTests.java`, `goal/CategoryGoalApiTests.java`, `user/UserApiTests.java`
- Docs: `docs/00` 6·9·18번 API 행과 목표 규칙(173행) 검사 순서, `docs/08` 해당 행

**Interfaces:**
- Consumes: Task 1의 엄격 coercion·타입 오류 처리기.
- Produces: `@Amount` — `@NotNull @Min(1) @Max(100_000_000) @ReportAsSingleViolation`, `message = "1 이상 100000000 이하의 정수여야 합니다."`, 대상 `FIELD, PARAMETER, RECORD_COMPONENT, TYPE_USE`(Task 4가 `JsonNullable<@Amount Long>`로 쓴다).
- Produces: `record BudgetRequest(@Amount Long amount)`, `record CategoryGoalRequest(@Amount Long amount)`, `record PetRequest(@NotNull(message = "CAT 또는 DOG만 허용합니다.") String petType)`. `from(JsonNode)`는 지운다.
- Produces: `BudgetService.put(long userId, String monthText, BudgetRequest request): MonthlyBudget`, `CategoryGoalService.put(long userId, String monthText, String categoryText, CategoryGoalRequest request): CategoryGoal`, `UserService.selectPet(long userId, PetRequest request): User`. Controller는 `@Valid @RequestBody`. CAT/DOG 허용 판단은 `UserService.validatePetType`에 그대로 둔다.

- [ ] **Step 1: 테스트 수정·추가**
  - `BudgetApiTests.putChecksMonthBeforeBody` → `putChecksBodyBeforeMonth`: `PUT /months/2099-01/budget` 본문 `{"amount":0}` → 400 `fields` 에 `amount`(month 아님). 올바른 본문이면 `month` 오류.
  - `BudgetApiTests.putRejectsLooseAmounts`: amount `"5000"`, `1.5`, `1.0`, `1e3`, `true`, `""`, `9223372036854775808` → 400, field `amount`, 문구 `형식이 올바르지 않습니다.`; `0`, `100000001`, `null`, 생략 → field `amount`, 문구 `1 이상 100000000 이하의 정수여야 합니다.`; `[]`·`"x"`·빈 본문 → `요청 본문을 읽을 수 없습니다.`, `fields` 없음. 행이 생기지 않음.
  - `CategoryGoalApiTests.validationOrderIsMonthCategoryBodyThenBudget` → `validationOrderIsBodyMonthCategoryThenBudget`: 미래 월 + 잘못된 category + `{"amount":0}` → field `amount`; 본문이 맞으면 `month` → `category` → 예산 없음 409 순서는 그대로.
  - `UserApiTests`: `petType` 생략·`null` → field `petType`, `CAT 또는 DOG만 허용합니다.`; `"cat"`·`"BIRD"` → 같은 문구; `{"petType":1}` → `형식이 올바르지 않습니다.`
- [ ] **Step 2: 실행해 실패 확인** — `./gradlew test --no-daemon --tests '*BudgetApiTests' --tests '*CategoryGoalApiTests' --tests '*UserApiTests'` → 순서·타입 테스트 FAIL.
- [ ] **Step 3: `@Amount`, 세 DTO, Service·Controller 시그니처 구현.** `RequestBodies.amount`/`requireObject`는 Task 3·4가 끝날 때까지 지출이 쓰므로 남긴다.
- [ ] **Step 4: 실행해 통과 확인** — 같은 명령 PASS, 전체 `./gradlew test --no-daemon` PASS.
- [ ] **Step 5: 문서 갱신 후 커밋** — `git commit -m "refactor: bind budget, goal and pet bodies as validated DTOs"`

### Task 3: 지출 생성 본문 DTO

**Files:**
- Create: `expense/ExpenseDate.java` + `expense/ExpenseDateValidator.java` (String 제약, `Clock` 주입)
- Create: `expense/CategoryName.java` + 검증기, `expense/Memo.java` + 검증기 (또는 한 파일에 애너테이션·검증기 — 빌더 판단)
- Modify: `expense/ExpenseRequest.java`, `expense/Expense.java`(`sameContentAs`), `expense/ExpenseController.java`, `expense/ExpenseService.java`
- Test: `expense/ExpenseApiTests.java`, `expense/ExpenseIdempotencyApiTests.java`
- Docs: `docs/00` 지출 생성·멱등성·월 상한(170·171행) 문장, "첫 오류" 표현

**Interfaces:**
- Consumes: `@Amount`(Task 2), Task 1 처리기.
- Produces: `@ExpenseDate` — 값이 `null`이거나 `\d{4}-\d{2}-\d{2}`가 아니거나 파싱 실패면 `YYYY-MM-DD 형식이어야 합니다.`, 2000-01-01 이전이거나 서울 기준 오늘(`LocalDate.ofInstant(clock.instant(), Asia/Seoul)`) 이후면 `2000-01-01부터 오늘까지만 허용합니다.` 검증기는 Spring이 주입하는 `Clock` 빈(테스트에서는 `MutableClock`)을 쓴다.
- Produces: `@CategoryName` — `null`은 통과, 그 외 `Category.parse`가 받지 않는 값이면 `허용되지 않는 카테고리입니다.`
- Produces: `@Memo` — `null`·공백만은 통과, NUL 문자 포함이면 `허용되지 않는 문자가 있습니다.`, 코드 포인트 100 초과면 `최대 100자입니다.`
- Produces: `record ExpenseRequest(@Amount Long amount, @ExpenseDate String expenseDate, @CategoryName String category, @Memo String memo)` + 정규화 접근자 `LocalDate date()`, `Category categoryOrDefault()`(null → `OTHER`), `String normalizedMemo()`(공백만 → null). `Expense.sameContentAs(ExpenseRequest)`는 정규화 값으로 비교.
- Produces: `ExpenseService.create(long userId, ExpenseRequest request, String idempotencyKey): CreateResult`.

- [ ] **Step 1: 테스트 수정·추가**
  - `createReportsFirstInvalidFieldInBodyOrder` → `createReportsAllInvalidFields`: `{"amount":0,"expenseDate":"x","category":"X","memo":"\u0000"}` → `fields[*].field`에 `amount`·`expenseDate`·`category`·`memo`가 모두 있음(순서 무관), 각 문구가 Global Constraints 값. 잘못된 본문 + `Idempotency-Key: bad` → `amount`만 있고 `Idempotency-Key` 없음. 행 0개.
  - `createRejectsLooseValues`: amount `"5000"`/`1.5`/`true` → `amount` 타입 문구; `{"memo":1234}` → `memo` 타입 문구; `{"memo":"1234"}` 포함 정상 본문 → 201, memo `"1234"`.
  - 멱등: 같은 키로 `memo:"  "`와 `memo` 생략을 차례로 보내면 두 번째가 200(정규화 비교). category 생략과 `"OTHER"`도 200.
  - 기존 서울 경계 테스트(`seoulTomorrowIsFutureJustBeforeSeoulMidnight` 등)는 문구·필드 기대값을 유지한 채 통과해야 한다.
- [ ] **Step 2: 실행해 실패 확인** — `./gradlew test --no-daemon --tests '*ExpenseApiTests' --tests '*ExpenseIdempotencyApiTests'` → 새 테스트 FAIL.
- [ ] **Step 3: 제약 3개, `ExpenseRequest`, `sameContentAs`, `create` 시그니처 구현.** 생성 경로의 `seoulToday()` 사용을 검증기로 옮긴다(PATCH가 아직 쓰면 남긴다).
- [ ] **Step 4: 실행해 통과 확인** — 같은 명령 PASS, 전체 PASS.
- [ ] **Step 5: 문서 갱신 후 커밋** — `git commit -m "refactor: bind the expense create body as a validated DTO"`

### Task 4: 지출 PATCH 본문 DTO와 정리

**Files:**
- Modify: `expense/ExpensePatch.java`, `expense/ExpenseController.java`, `expense/ExpenseService.java`
- Modify/정리: `expense/ExpenseFields.java`, `common/RequestBodies.java`(본문 파싱용으로만 쓰이던 코드 삭제, `MAX_AMOUNT`·`idempotencyKey` 등 계속 쓰이는 것은 유지)
- Test: `expense/ExpensePatchTests.java`, `expense/ExpenseApiTests.java`
- Docs: `docs/00` 78행(PATCH 규칙)과 월 상한의 PATCH 검사 순서, `docs/08` 76행(공통 실패 응답), 필요 시 `docs/10`에 변경 기록 한 줄

**Interfaces:**
- Consumes: `@Amount`, `@ExpenseDate`, `@CategoryName`, `@Memo`.
- Produces: `record ExpensePatch(JsonNullable<@Amount Long> amount, JsonNullable<@ExpenseDate String> expenseDate, JsonNullable<@NotNull(message = "허용되지 않는 카테고리입니다.") @CategoryName String> category, JsonNullable<@Memo String> memo)`. 생략(`undefined`)은 검증하지 않는다. `Expense applyTo(Expense current, OffsetDateTime now)`는 present인 필드만 바꾸고 memo는 정규화(공백만 → null, `null` → 삭제).
- Produces: `ExpenseService.update(long userId, long id, ExpensePatch patch): Expense`. 순서: (`@Valid` 본문) → 잠금 조회 404 → 월 이동 시 상한 → UPDATE.

- [ ] **Step 1: 테스트 수정·추가**
  - `ExpensePatchTests.omittedFieldsKeepCurrentValuesAndNullMemoClears`를 `JsonNullable` 입력으로 바꾼다(생략 유지, `memo` null 삭제, 공백 메모 → null).
  - `patchChecksBodyShapeThenExistenceThenFields` → `patchChecksBodyBeforeExistence`: 없는 id에 `[]` → `요청 본문을 읽을 수 없습니다.`; 없는 id에 `{"amount":0}` → 400 field `amount`(404 아님); 없는 id에 `{"amount":1}` → 404 `EXPENSE_NOT_FOUND`; 있는 id에 `{"amount":0,"category":"X"}` → `amount`·`category` 둘 다.
  - `patchRejectsExplicitNullForRequiredFieldsAndInvalidValues`: `amount`·`expenseDate`·`category`의 `null` → 각 필드 이름 그대로(`fields[].field`가 정확히 `amount` 등, 컨테이너 경로 아님)와 Global Constraints 문구; `{"memo":null}` → 200, memo null.
  - `patchRejectsLooseValues`: `{"amount":"5000"}`, `{"amount":1.5}`, `{"memo":1234}` → 각 필드 타입 문구, 행 불변.
- [ ] **Step 2: 실행해 실패 확인** — `./gradlew test --no-daemon --tests '*ExpensePatchTests' --tests '*ExpenseApiTests'` → FAIL.
- [ ] **Step 3: `ExpensePatch`, `update` 시그니처 구현, 안 쓰이게 된 `ExpenseFields`·`RequestBodies` 코드와 import 정리.** `JsonNullable` 위반의 필드 경로가 `memo`처럼 나오지 않으면 `ApiExceptionHandler`의 `MethodArgumentNotValidException` 처리에서 컨테이너 요소 부분을 떼어 필드 이름만 쓴다.
- [ ] **Step 4: 실행해 통과 확인** — 같은 명령 PASS, `grep -rn "JsonNode" src/main/java` 결과에 요청 본문 파싱이 남지 않음(처리기 등 다른 용도는 무관), 전체 `./gradlew cleanTest test --no-daemon` PASS.
- [ ] **Step 5: 문서 갱신 후 커밋** — `git commit -m "refactor: bind the expense PATCH body with JsonNullable and drop JsonNode parsing"`

### 마무리

- [ ] docs `00`·`05`·`08`(필요 시 `09`·`10`)에 "검증 순서 = 첫 오류 순서", "month → 본문" 같은 옛 문장이 남지 않았는지 grep으로 확인한다(`04`, `docs/superpowers/` 과거 기록 제외).
- [ ] qa-engineer 독립 검토 → 발견 사항 반영 → PR(develop 대상).
