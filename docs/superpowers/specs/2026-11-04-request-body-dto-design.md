# 요청 본문 DTO 전환 설계

작성일: 2026-11-04. 상태: 사용자 결정 반영(2026-11-04).

## 1. 의도와 이유

지금 본문을 받는 API 다섯 곳은 `@RequestBody JsonNode`로 받은 뒤 `ExpenseRequest`·`ExpensePatch`·`BudgetRequest`·`PetRequest`·`CategoryGoalRequest`의 `from(JsonNode)`이 직접 파싱한다. 이 다섯 곳을 auth(`LoginRequest`, `RefreshTokenRequest`)처럼 **DTO record + `@Valid` Bean Validation**으로 바꾼다.

사용자가 밝힌 이유:

- `body.get("amount")`처럼 필드 이름을 문자열로 써서 오타를 컴파일러가 잡지 못한다.
- 다른 백엔드 코드에서 JsonNode 본문 파싱은 흔하지 않다. 흔한 DTO 형태가 더 읽기 쉽다.

프로젝트에는 이미 `spring-boot-starter-validation`과 `MethodArgumentNotValidException` → 필드별 400 처리기가 있다. 새 방식을 들이는 것이 아니라 프로젝트 안의 두 방식을 하나로 통일하는 작업이다.

## 2. 목표

- 다섯 API의 본문을 DTO record로 받는다. 필드 이름은 record 컴포넌트가 된다.
- PATCH의 "생략 = 유지, 명시적 `null` = 삭제(memo) / 400(그 외)" 의미를 그대로 지킨다.
- 앱 화면에 그대로 보이는 **필드 이름과 오류 메시지 문구를 바꾸지 않는다.**
- 지금 서버가 거부하는 값을 DTO로 바꾼 뒤에도 모두 거부한다. 특히 금액 `"5000"`(문자열), `1.5`, `1.0`, `1e3`, `true`, 메모에 숫자 등이다.
- 계약 문서와 테스트를 새 동작에 맞게 고친다.

## 3. 비목표

- 응답 DTO, OpenAPI 문서 자동 생성, 앱 코드 변경.
- 알 수 없는 필드 거부. 지금처럼 무시한다.
- 경로 변수·쿼리 파라미터(`{month}`, `{category}`, `limit`, `cursor`, `date`) 검증 방식 변경.
- auth DTO 변경. 전역 Jackson 설정의 영향은 받지만 코드는 그대로 둔다.

**실패로 볼 것(반목표):** DTO로 바꾼 뒤 `1.5`가 1로 저장되거나 `"5000"`이 받아들여지는 것처럼, 검증이 조용히 느슨해지는 것.

## 4. 사용자가 정한 계약 변경

| 항목 | 지금 | 바뀐 뒤 |
|---|---|---|
| 필드 오류 개수 | 정해진 순서의 **첫 오류 하나** | 틀린 필드 **모두** (`fields` 배열, 순서 보장 없음) |
| 본문 검증 시점 | Service가 정한 위치(경로 검증·존재 확인 뒤일 수 있음) | Controller 진입 전(`@Valid`). **본문 검증이 경로 변수 검증·존재 확인·예산 존재 확인보다 먼저** |
| JSON 타입이 틀린 값의 문구 | 필드별 문구(예: amount `"5000"`·`1.5`·`true` → `"1 이상 100000000 이하의 정수여야 합니다."`, memo 숫자 → `"문자열이어야 합니다."`, petType·category·expenseDate 숫자 → 각 필드 문구) | 그 필드 이름 + **`"형식이 올바르지 않습니다."`** (code·message는 필드 오류와 같음). 값은 지금처럼 거부한다. JSON 숫자 타입 memo(`{"memo":1234}`)도 계속 거부한다(숫자가 든 문자열은 지금처럼 허용) |

검사 순서 변화:

| API | 지금 | 바뀐 뒤 |
|---|---|---|
| `POST /expenses` | 본문 → `Idempotency-Key` 형식 → 기존 키 재요청 → 월 상한 | 같음 |
| `PATCH /expenses/{id}` | 본문이 객체인지 → 존재·소유 404 → 필드 → 월 이동 시 상한 | 본문(필드 포함) → 404 → 상한 |
| `PUT /months/{month}/budget` | `month` → 본문 | 본문 → `month` |
| `PUT /months/{month}/category-goals/{category}` | `month` → `category` → 본문 → 예산 존재 → 합계 | 본문 → `month` → `category` → 예산 존재 → 합계 |
| `PUT /users/me/pet` | 본문 | 같음 |

근거(앱 저장소 `petgyebu_app` develop 확인, 2026-11-04):

- 지출 생성·수정 화면은 `fields` 전체를 순회해 아는 필드의 `message`를 입력칸 아래에 그대로 띄운다. 여러 오류가 와도 동작한다.
- 예산·목표 화면은 `fieldMessage('amount')`·`fieldMessage('month')`로 메시지를 그대로 띄운다.
- 앱은 금액 상한(1억)·메모 100자·서울 기준 오늘을 이미 클라이언트에서 맞추고, 숫자 키패드와 코드로 본문을 만든다. 따라서 타입 오류나 여러 필드 동시 오류, 검사 순서 차이에 따른 결과 변화는 정상 앱에서 생기지 않는다.
- 본문 오류 400은 지출 존재 여부와 무관하게 나가므로 다른 사용자의 지출 ID 존재 여부가 드러나지 않는다.

## 5. 바꾸지 않는 계약

- 오류 응답 형태 `{code, message, fields?}`와 `code: INVALID_REQUEST`, `message: "요청 값이 올바르지 않습니다."`(필드 오류) / `"요청 본문을 읽을 수 없습니다."`(본문을 읽을 수 없음).
- 각 필드의 오류 메시지 문구(JSON 타입이 맞는 값의 검증 실패). 지금 `RequestBodies`·`ExpenseFields`·`PetRequest`·`Category.parse`에 있는 문구를 그대로 쓴다. JSON 타입이 틀린 값만 4절대로 일반 문구가 된다.
- 필드 규칙: 금액 1 이상 1억 이하 정수, `expenseDate`는 `YYYY-MM-DD`이며 2000-01-01부터 **서울 기준 오늘**까지, `category`는 7개 값 대소문자 구분, 생성에서 `category` 생략·`null`은 `OTHER`, 메모는 생략·`null`·공백만이면 `null`·NUL 문자 거부·코드 포인트 100자, `petType`은 문자열 필수(CAT/DOG 판단은 지금처럼 Service).
- PATCH: 보낸 필드만 바꾼다. `memo: null`은 메모 삭제, `amount`·`expenseDate`·`category`의 명시적 `null`은 400.
- 성공 응답, 상태 코드, 409 코드들, 멱등성 비교 규칙.

## 6. 정해진 구현 방식

- **PATCH의 3상태:** `org.openapitools:jackson-databind-nullable`의 `JsonNullable<T>`를 쓴다(아래 실험에서 Jackson 3 지원 확인). 버전은 실험에서 검증한 **0.2.12**로 고정하고 `implementation`으로 넣는다. Spring Boot의 `spring.jackson.find-and-add-modules`(기본 true)가 모듈을 자동 등록하는 것을 실험에서 확인했다. 프로덕션 컨텍스트에서도 등록되는지 테스트로 고정한다.
- **엄격한 타입 변환:** 앱 전역 `JsonMapper`에 `JsonMapperBuilderCustomizer`로 coercion 설정을 넣는다. 정수(`LogicalType.Integer`)는 문자열·실수·불리언·빈 문자열에서 변환을 `Fail`로, 문자열(`LogicalType.Textual`)도 숫자·불리언에서의 변환을 `Fail`로 막는다. 다른 논리 타입(예: 생성 DTO의 날짜를 `LocalDate`로 받는다면 그 타입)에서 같은 문제가 있는지는 빌더가 확인한다.
- **경로 변수 검증은 지금 위치에 둔다.** `Months.parsePastOrCurrent`, `Category.parse`는 Service에서 지금처럼 호출한다.
- **오류는 모두 한꺼번에:** Bean Validation 기본 동작(모든 제약 위반 수집)을 쓴다. `@GroupSequence` 등으로 첫 오류만 남기지 않는다.
- **(내 판단) 한 필드의 여러 위반:** 한 필드가 제약 여러 개를 함께 어기면(예: 메모 NUL + 100자 초과) `fields`에 그 필드가 여러 번 들어가도 된다. 앱은 지출 화면에서 필드별로 하나(`Object.fromEntries`로 마지막 값), 예산·목표 화면에서 `fieldMessage`로 하나를 띄우므로 동작에 문제가 없다. 테스트는 이 경우 필드 이름 포함 여부만 확인한다.

## 7. 빌더에게 맡기는 것 (내 판단 표시)

- **타입 오류의 필드 정보(사용자 결정):** 지금 `HttpMessageNotReadableException` 처리기는 `fields` 없이 "요청 본문을 읽을 수 없습니다."를 낸다. 원인이 특정 필드의 타입 불일치(`MismatchedInputException` 등 경로가 있는 예외)이면, 그 필드 이름과 `"형식이 올바르지 않습니다."`로 `fields`를 채운 `INVALID_REQUEST`("요청 값이 올바르지 않습니다.")를 낸다. 쿼리 파라미터 타입 오류 처리기(`MethodArgumentTypeMismatchException`)와 같은 문구다. JSON 문법 오류·본문 없음·객체가 아닌 본문은 지금처럼 `fields` 없는 "요청 본문을 읽을 수 없습니다."다. 이 경우 타입 오류는 Jackson이 첫 오류에서 멈추므로 한 필드만 나온다.
- **(내 판단) 서울 기준 오늘 규칙:** 주입된 `Clock`을 쓰는 커스텀 제약으로 구현해 다른 필드 오류와 함께 한꺼번에 나오게 하는 쪽을 우선한다. 어렵다면 Service 검사로 두고, 그 경우 날짜 미래 오류는 다른 필드 오류와 따로 나온다는 점을 문서에 적는다.
- `expenseDate`·`category`·`memo`를 DTO에서 `String`으로 받을지 `LocalDate`·`Category` 타입으로 받을지. 어느 쪽이든 형식 오류 메시지가 지금 문구(`"YYYY-MM-DD 형식이어야 합니다."`, `"허용되지 않는 카테고리입니다."`)로 나와야 한다. 타입으로 받으면 Jackson 변환 오류가 7절 첫 항목의 일반 문구로 바뀌므로 `String` + 제약이 더 단순할 가능성이 크다.
- 메모 정규화(공백만 → `null`)와 기본값(`category` → `OTHER`)을 DTO 쪽(compact constructor 등)에 둘지 Service에 둘지.
- `JsonNullable`과 Bean Validation 연동(라이브러리의 ValueExtractor 등록 여부)과 PATCH 필드 제약 표현 방법.
- `RequestBodies`·`ExpenseFields`에서 쓰이지 않게 된 코드 정리. 경로·헤더 검증(`Idempotency-Key` 형식 등)에 계속 쓰이는 것은 남긴다.
- 테스트를 어떻게 고칠지. 첫 오류 순서를 고정하던 테스트는 "이 필드 오류가 들어 있다" / "여러 필드가 함께 나온다" / "본문 오류가 경로·존재 오류보다 먼저다"로 바꾼다. 실험에서 확인한 거부 값(`"5000"`, `1.5`, `1.0`, `1e3`, `true`, `""`, 메모 숫자)은 HTTP 테스트로 고정한다.

## 8. 고칠 문서

`docs/00-renewal-development-guide.md`(검사 순서 문장들: 지출 생성·PATCH·월 상한·예산·목표·펫, "첫 오류" 표현), `docs/08-feature-implementation-map.md` 76행(공통 실패 응답), `docs/05-infra-stack.md`(새 의존성), 필요한 경우 `docs/09`·`docs/10`. `04`와 `docs/superpowers/` 아래 과거 기록은 고치지 않는다. 고친 뒤 docs `00`이 다른 문서보다 우선이라는 원칙대로 모순이 없게 한다.

## 9. 완료 기준

- 다섯 Controller가 `JsonNode`를 받지 않는다. `src/main`에서 요청 본문 파싱 목적의 `JsonNode` 사용이 사라진다.
- 4절·5절 계약을 HTTP 테스트로 확인한다: 메시지 문구 동일, 여러 필드 동시 오류, 본문 오류가 경로·404보다 먼저, PATCH 3상태, 엄격한 거부 값.
- `./gradlew test --no-daemon` 통과.
- qa-engineer 독립 검토 통과 후 PR.

## 10. 선행 조건

PR #55(목표 PUT 잠금 제거)가 `CategoryGoalService`를 바꾼다. #55를 develop에 병합한 뒤 그 develop에서 브랜치를 만든다.

## 부록. 실험 기록 (2026-11-04)

로컬 브랜치 `experiment/dto-null-semantics`(커밋 `ca85d28`, 푸시하지 않음), 테스트 `experiment/JacksonNullSemanticsTests`(7개)·`DtoBodyErrorOrderTests`(3개). 대상은 Jackson 자동 구성만 띄운 Spring Boot `JsonMapper`(boot)와 `JsonMapper.builder().build()`(plain). 아래는 모두 실행해서 관찰한 결과다. 실험 테스트 10개와 전체 295개가 통과했다.

**mapper 차이:** 관련 기능 플래그 기본값은 같다(`FAIL_ON_UNKNOWN_PROPERTIES` false, `FAIL_ON_NULL_FOR_PRIMITIVES` true, `ACCEPT_FLOAT_AS_INT` true, `ALLOW_COERCION_OF_SCALARS` true). 다른 것은 모듈뿐이다. boot는 클래스패스의 `JsonNullableModule`을 자동 등록하고 plain은 모듈이 없다.

**생략과 명시적 null 구분**

| 방식 | `{}` | `{"memo":null}` | `{"memo":"x"}` | 구분 |
|---|---|---|---|---|
| record `String memo` | null | null | x | 안 됨 |
| record `Optional<String> memo` | `Optional.empty` | `Optional.empty` | `Optional[x]` | 안 됨 |
| 클래스 필드 `Optional<String>` | Java null | `Optional.empty` | `Optional[x]` | 되지만 Optional에 null을 담는 편법 |
| record `JsonNullable<String>` (boot) | `undefined` | `JsonNullable[null]` | `JsonNullable[x]` | 됨 |
| record `JsonNullable<String>` (plain, 모듈 없음) | null | null | `InvalidDefinitionException` | 안 됨 |

`jackson-databind-nullable`은 0.2.10부터 `tools.jackson.core:jackson-databind`(Jackson 3)를 의존성으로 두고 `JsonNullableJackson3Module`을 제공한다. 실험은 0.2.12(프로젝트 jackson-databind 3.1.5와 함께)로 했다. 0.2.13은 공개 2주 미만이라 확인하지 않았다.

**숫자 변환 기본값 (boot와 plain 같음)**

| 입력 | `long` | `Long` |
|---|---|---|
| `"5000"` | 5000 | 5000 |
| `1.5` | 1 | 1 |
| `1.0` | 1 | 1 |
| `1e3` | 1000 | 1000 |
| `true` | 예외 | 예외 |
| `""` | 예외 | null |
| `null` | 예외 | null |
| 2^63 | 예외 | 예외 |

**엄격 설정:** `MapperFeature.ALLOW_COERCION_OF_SCALARS`를 끄면 문자열·불리언은 막지만 실수는 통과한다. `DeserializationFeature.ACCEPT_FLOAT_AS_INT`를 끄면 실수는 막지만 문자열은 통과한다. `withCoercionConfig(LogicalType.Integer, …)`로 String·Float·Boolean·EmptyString을 `CoercionAction.Fail`로 두면 `"5000"`, `1.5`, `1.0`, `1e3`, `true`, `""`를 모두 거부하고 래퍼의 `null`만 통과한다. `JsonMapperBuilderCustomizer`로 넣어도 같다. 범위(1~1억)는 별도 검증이 필요하다.

**알 수 없는 필드:** boot·plain 모두 무시한다.

**HTTP 경로 (테스트 전용 DTO 컨트롤러, MockMvc):** 없는 id에 `{"amount":"abc"}`를 PATCH하면 400 `INVALID_REQUEST`, `"요청 본문을 읽을 수 없습니다."`, `fields` 없음. 같은 id에 올바른 본문이면 404. 같은 요청을 지금 API(`PATCH /api/v1/expenses/999`)에 보내면 404가 먼저다. 기본 설정의 DTO 경로에서는 `{"amount":"5000","foo":2}`가 200(amount=5000), `{"amount":1.5}`가 200(amount=1)으로 저장됐다.

**미검증:** 0.2.13 동작, `JsonNullable`과 Bean Validation 연동, `JsonNullable` 직렬화, `FAIL_ON_UNKNOWN_PROPERTIES`를 켰을 때 응답, 엄격 설정을 전역에 넣었을 때 다른 엔드포인트 영향.
