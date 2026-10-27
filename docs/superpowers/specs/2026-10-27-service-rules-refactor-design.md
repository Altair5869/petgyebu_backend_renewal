# Service 규칙 중심 구조 개선 설계

- 작성: 2026-10-27
- 참고: [moabam `CouponService`](https://github.com/team-moabam/moabam-BE/blob/develop/src/main/java/com/moabam/api/application/coupon/CouponService.java)
- 기준: [리뉴얼 개발 기준](../../00-renewal-development-guide.md), [DB 설계](../../09-db-design.md) 4절, [인프라·기술 스택](../../05-infra-stack.md)
- 범위 밖: 인증(`AuthService`)·소셜 로그인, 새 기능, API·DB 스키마 변경, JPA·QueryDSL·Lombok 도입

## 1. 의도

Service 메서드를 위에서 아래로 읽으면 그 기능의 비즈니스 규칙 목록으로 읽히게 한다. 지금은 Service가 요청 본문 형식 검사, 규칙, 조합, 트랜잭션, DB 오류 번역을 한데 들고 있고(`ExpenseService` 255줄 중 약 100줄이 `JsonNode` 검사), 월 합계 규칙(`지출 없으면 0`, 카테고리별 합·건수)은 SQL 안에 있다.

사용자 결정:

- 목표는 "Service가 규칙 중심으로 읽히는 구조"다.
- 합산 같은 계산은 DB 쿼리가 아니라 Service 계층에서 한다. DB는 행을 조회만 한다.
- 홈도 통계와 같은 방식(행 조회 → Service에서 합산)으로 통일한다.
- 탈퇴용 `DELETE`를 각 Repository로 옮기는 정리도 범위에 넣는다.

## 2. 목표·비목표·실패 조건

목표

- Service public 메서드가 `validate…`/도메인 질의/Repository 호출의 짧은 나열로 읽힌다.
- 요청 형식 검사는 요청 객체로, 월 집계 계산은 도메인 객체로 옮긴다.
- 중복(금액 검증, 본문 객체 검사와 오류, 월 범위 계산)을 한 곳으로 모은다.

비목표

- 인증·세션·통계 응답 형식 변경, 새 엔드포인트, 성능 개선.
- 컨트롤러의 응답 변환 구조 변경(지금도 얇다).

실패 조건(기능은 돌아가도 실패로 본다)

- API 응답이 바뀐다: 상태 코드, 오류 `code`, `field`, 메시지, 응답 필드·값·정렬.
- **여러 값이 동시에 틀린 요청에서 먼저 나가는 오류가 바뀐다.** 지금 순서를 유지한다(4절).
- 동시성 보호가 약해진다: 멱등키 `UNIQUE` 충돌 재조회, 예산·로그인 `ON CONFLICT`, 탈퇴의 한 트랜잭션·FK 충돌 재시도, 수정·삭제의 `WHERE id AND user_id` 소유권 조건.
- 기존 테스트를 고쳐서 통과시킨다(테스트는 계약이다. 이동한 클래스명 참조 같은 컴파일 수정만 허용).

## 3. 결정한 구조

### 3.1 월 집계: DB는 조회, 계산은 도메인 객체

`ExpenseRepository.sumAmount`·`sumByCategory`를 없애고 한 조회로 바꾼다.

```sql
SELECT category, amount FROM expenses
WHERE user_id = :userId AND expense_date >= :from AND expense_date < :to
```

- 반환은 두 필드 record(예: `ExpenseAmount(Category category, long amount)`). 기존 인덱스 `ix_expenses_user_date_id`를 그대로 쓰며 마이그레이션은 없다.
- 계산 규칙은 도메인 객체(예: `MonthlySpending`)에 둔다: 총액(행 없으면 0), 총 건수, 카테고리별 금액·건수(지출 있는 카테고리만).
- 통계의 정렬(금액 내림차순, 동률은 `Category` 선언 순서)과 `sharePercent`(`HALF_UP` 소수 첫째 자리, 합계 보정 없음)는 지금처럼 `StatsService`의 규칙으로 남긴다. `BudgetUsage`는 그대로 쓴다.
- 홈과 통계가 같은 조회·같은 도메인 객체를 쓴다.
- 통계는 합계와 카테고리를 한 조회에서 계산하므로 `REPEATABLE READ`를 일반 `@Transactional(readOnly = true)`로 낮춘다.

### 3.2 요청 형식 검사: 요청 객체

- 요청 객체(예: `ExpenseRequest`, `ExpensePatch`, `BudgetRequest`, `PetRequest`)가 `JsonNode`를 직접 파싱하는 정적 팩토리를 가진다. Jackson 자동 바인딩은 쓰지 않는다. 이유: 문자열 숫자(`"100"`)·실수 거부, PATCH의 "생략 = 유지 / `null` = 지움" 구분을 지금 그대로 지키기 위해서다.
- **파싱 호출 위치는 Service 안, 지금 검증이 일어나는 지점 그대로다.** 컨트롤러는 계속 `JsonNode`를 넘긴다. 그래야 경로 값·존재 확인과 본문 검사의 순서가 유지된다(4절).
- 금액 범위(1 ~ 100,000,000 정수)와 "요청 본문을 읽을 수 없습니다." 오류는 공용 한 곳으로 모은다.

### 3.3 Service의 규칙

Service에 이름 붙은 메서드로 남는 규칙(예시 이름):

- 지출: 멱등키 재요청은 내용이 같아야 한다(다르면 409). 판단은 도메인 질의(예: `expense.sameContentAs(request)`)로 묻는다. 수정·삭제 대상이 없으면 404. 커서는 발급한 사용자·월에서만 유효.
- 예산: 현재 월만 설정한다.
- 홈: 펫이 먼저, 그다음 현재 월 예산.
- 사용자: 펫은 `CAT`·`DOG`만, 탈퇴는 지출 → 예산 → 사용자 순.
- DB 오류 번역(FK 위반 → 401)은 Service에 남긴다.

### 3.4 Repository 경계

- 탈퇴용 `DELETE FROM expenses WHERE user_id`는 `ExpenseRepository`로, `DELETE FROM monthly_budgets WHERE user_id`는 `MonthlyBudgetRepository`로 옮긴다. SQL은 같다. `UserService.withdraw`가 한 트랜잭션 안에서 세 Repository를 같은 순서로 호출하고, FK 충돌 재시도도 그대로다.
- 지출 수정은 Spring Data JDBC `save()`로 바꾸지 않는다. `save()`는 `WHERE id`만으로 갱신해 소유권 조건이 빠진다. 명시적 `UPDATE … WHERE id AND user_id`를 유지한다.
- 나머지 쿼리(목록·커서, 예산·로그인 upsert, 세션 조건부 갱신)는 그대로다.

## 4. 지켜야 할 오류 순서

| API | 현재 순서 |
|---|---|
| `POST /expenses` | 본문 객체 → amount → expenseDate(형식, 2000-01-01~서울 오늘) → category → memo → `Idempotency-Key` 형식 → 재요청 내용 비교 |
| `PATCH /expenses/{id}` | 본문 객체 → 존재·소유(404) → 보낸 필드만 amount → expenseDate → category → memo |
| `PUT /months/{month}/budget` | month 형식·범위 → 현재 월 여부 → 본문 객체 → amount |
| `PUT /users/me/pet` | 본문 객체 → petType |
| 목록 | month → limit → cursor |

지출일 범위(2000-01-01 ~ 서울 오늘)는 규칙이지만 category·memo보다 먼저 검사돼야 한다. 그래서 **제 판단으로** 이 범위 검사는 요청 객체 파싱 안(오늘 날짜를 인자로 받음)에 둔다. Service로 빼면 순서가 바뀐다.

## 5. 검증

- `./gradlew test --no-daemon` 전체 통과. 기존 API 테스트(`ExpenseApiTests`, `ExpenseIdempotencyApiTests`, `BudgetApiTests`, `HomeApiTests`, `StatsApiTests`, `UserApiTests`)를 수정 없이 통과시킨다.
- 새 단위 테스트: 월 집계 도메인 객체(빈 목록 0원·0건·빈 카테고리, 여러 카테고리 합·건수, 25억 원대 합계), 요청 객체의 생략/`null` 구분.
- 오류 순서를 고정하는 API 테스트가 없는 조합이 있으면 먼저 추가한 뒤 리팩터링한다.
- 성능: `perf/explain.sql`의 `sumAmount`·`sumByCategory`를 새 조회로 바꾸고, `perf/README.md` 절차로 EXPLAIN과 k6 소규모 측정을 다시 실행해 `perf/results/`에 기록한다. 기준(2026-10-25, 20 VU p95): 홈 14ms, 통계 19ms, heavy 통계 27ms.
  - **사용자 결정: 느려져도 3.1 구조(행 조회 → Service 집계)를 유지한다.** 홈만 `SUM`으로 되돌리는 예외는 두지 않는다. 결과는 수치 그대로 기록하고 보고한다.
  - 제 판단: 측정은 집계 이동(3.1) 직후, 요청 객체·문서 작업 전에 실행한다. 큰 차이가 나면 원인을 일찍 볼 수 있다.

## 6. 문서 갱신

- `AGENTS.md` 18행: "월 통계는 명시적 쿼리로 계산한다" → "월 지출 행은 명시적 쿼리로 조회하고 합계·카테고리 집계는 Service의 도메인 객체에서 계산한다".
- `docs/09-db-design.md` 4절: `COALESCE(SUM)`·`GROUP BY` 문장을 행 조회 + 애플리케이션 집계로.
- `docs/05-infra-stack.md` 14·26·49행의 "명시적 월 집계 쿼리", "월 `SUM`·`GROUP BY` 집계" 문구.
- `docs/10-task-backlog.md`의 M-008·M-009 검증 기록은 과거 기록이므로 고치지 않고, 바뀐 사실을 짧은 후속 기록으로 덧붙인다.

## 7. 빌더에게 맡기는 것

- 클래스·메서드 이름, 패키지 위치(요청 객체와 `MonthlySpending`을 어느 패키지에 둘지).
- 월 범위 `[첫날, 다음 달 첫날)` 계산을 `Months`에 어떤 형태로 둘지.
- 작업 순서와 커밋 단위.
