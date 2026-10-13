# 기능 구현 맵: Petgyebu 리뉴얼 MVP

- 갱신: 2026-10-11
- 기준: [리뉴얼 개발 기준](00-renewal-development-guide.md), [기능명세](02-requirements-features.md), [스프린트 계획](06-sprint-plan.md)
- 상태: 구현 범위·의존 관계를 보여주는 계획. 현재 코드의 구현 완료 표시가 아니다.
- API 접두사: `/api/v1`; `{month}`는 `YYYY-MM`이다. **15개 동작, 11개 고유 경로, 3개 논리 테이블**이다.

## 1. 기능과 구현 책임

| 기능 | 스프린트 | 서버 책임 | 사용하는 데이터 |
|---|---:|---|---|
| `F-M01` 계정·인증 | 0 | 카카오·구글 계정 식별, 자체 토큰 발급·교체·무효화, 한 활성 로그인, 내 정보, 탈퇴 트랜잭션 | `users`; 탈퇴 시 `expenses`, `monthly_budgets` 명시적 삭제 |
| `F-M02` 펫 선택 | 1 | `CAT`·`DOG` 선택·변경. 별도 펫 테이블과 성장 계산 없음 | `users.pet_type` |
| `F-M03` 수동 지출 | 1 | 생성·월별 커서 목록·단건 조회·부분 수정·삭제, 입력 검증, 사용자 소유권 확인 | `expenses` |
| `F-M04` 월 예산 | 2 | 월별 예산 조회, 현재 월에만 설정·변경, 동시 PUT의 단일 upsert | `monthly_budgets` |
| `F-M06` 현재 홈 | 2 | 현재 월의 지출 합계·예산 잔액·사용률·펫 상태 계산, 미설정 온보딩 오류 | `users`, `expenses`, `monthly_budgets` |
| `F-M05` 월별 통계 | 3 | 현재·과거 월 총액·건수·카테고리 금액·건수·비중 계산. 과거 월 펫 응답 없음 | `expenses`, `monthly_budgets` |

API 계층은 인증된 사용자와 입력을 확인하고, 월 집계 계층은 같은 사용자·월 범위의 DB 원본 값으로 홈과 통계를 계산한다. 특정 클래스명·패키지나 토큰·커서의 내부 인코딩은 이 문서에서 고정하지 않는다. 스키마와 인덱스는 `09-db-design.md`를 따르며, 계약이 충돌하면 `00`의 데이터 계약을 우선한다.

## 2. API 전체 목록

| # | 기능 | 메서드 | 경로 | 요청·응답 핵심 | 데이터 |
|---:|---|---|---|---|---|
| 1 | `F-M01` | POST | `/api/v1/auth/login/{provider}` | 카카오·구글 로그인 → `accessToken`, `refreshToken`, `accessExpiresInSeconds`. 본문 `{credential}`, 의미는 잠정 | `users` |
| 2 | `F-M01` | POST | `/api/v1/auth/refresh` | 현재 `refreshToken` → 두 토큰 교체, `accessExpiresInSeconds` | `users` |
| 3 | `F-M01` | POST | `/api/v1/auth/logout` | 현재 `refreshToken`으로 활성 로그인 종료 → 204 | `users` |
| 4 | `F-M01` | GET | `/api/v1/users/me` | `id`, `provider`, `petType`; 선택 전 `petType: null` | `users` |
| 5 | `F-M01` | DELETE | `/api/v1/users/me` | 지출 → 월 예산 → 사용자 순으로 한 트랜잭션에서 탈퇴 → 204 | 3개 테이블 |
| 6 | `F-M02` | PUT | `/api/v1/users/me/pet` | `petType: CAT\|DOG` 선택·변경 → 갱신된 내 정보 | `users` |
| 7 | `F-M06` | GET | `/api/v1/home` | 현재 월의 `month`, `petType`, `petState`, `budgetAmount`, `spentAmount`, `usagePercent`, `budgetBalanceAmount` | 3개 테이블 |
| 8 | `F-M04` | GET | `/api/v1/months/{month}/budget` | 해당 월 `{month, amount}`; 미설정이면 `amount: null` | `monthly_budgets` |
| 9 | `F-M04` | PUT | `/api/v1/months/{month}/budget` | **현재 월만** `{amount}`로 생성·변경 → `{month, amount}`, 항상 200 | `monthly_budgets` |
| 10 | `F-M03` | POST | `/api/v1/expenses` | `amount`, `expenseDate`, 선택 `category`·`memo` → 지출 응답, 201 | `expenses` |
| 11 | `F-M03` | GET | `/api/v1/months/{month}/expenses` | `limit`, `cursor` → `items`, `nextCursor` | `expenses` |
| 12 | `F-M03` | GET | `/api/v1/expenses/{expenseId}` | 본인 지출 단건·수정 초기값 → 지출 응답 | `expenses` |
| 13 | `F-M03` | PATCH | `/api/v1/expenses/{expenseId}` | 전달한 필드만 수정, 날짜의 월 이동 가능 → 지출 응답 | `expenses` |
| 14 | `F-M03` | DELETE | `/api/v1/expenses/{expenseId}` | 본인 지출 삭제 → 204 | `expenses` |
| 15 | `F-M05` | GET | `/api/v1/months/{month}/stats` | 월별 숫자 통계와 카테고리 금액·건수·비중, 펫 필드 없음 | `expenses`, `monthly_budgets` |

`PUT /users/me/pet`, 지출 조회·수정, 예산 조회, 홈·통계 조회 등 본문이 있는 성공 응답은 200이다. 지출 생성은 201, 지출 삭제·로그아웃·탈퇴는 204다. 로그인·갱신의 응답 필드와 오류 계약은 `00`을 따른다.

## 3. 요청·응답과 오류의 공통 계약

### 인증과 진입

- 액세스 토큰은 15분, 리프레시 토큰은 마지막 사용 후 30일 유효하다. 갱신은 리프레시 토큰을 교체한다. 새 로그인·로그아웃·탈퇴 후 이전 액세스 토큰도 현재 `active_session_id`와 불일치해 거부된다.
- 앱은 `GET /users/me`로 펫을 확인하고 현재 월 예산을 조회한 뒤 홈에 들어간다. `GET /home`은 현재 월만 반환한다. 펫이 없으면 409 `PET_REQUIRED`, 현재 월 예산이 없으면 409 `BUDGET_REQUIRED`다. 둘 다 없으면 펫 선택을 먼저 안내한다.
- 공급자는 `KAKAO`, `GOOGLE`만 사용하고 각 공급자의 사용자 ID는 `provider_user_id`에 저장한다. 같은 사람이 다른 공급자를 쓰면 별도 계정이다. 앱인토스에 맞춘 공급자별 인증 전달 상세는 후속 결정이다.

### 지출·예산

- 지출 응답은 생성·단건 조회·수정·목록의 각 `item`에서 공통으로 `id`, `amount`, `expenseDate`, `category`, `memo`를 반환한다. `memo`가 없으면 `null`이다. DB의 생성·수정 시각은 화면 응답에 넣지 않는다.
- 지출 금액은 양의 원화 정수이며 날짜는 `Asia/Seoul` 기준 오늘 또는 과거다. 생성에서 카테고리 생략·`null`은 `OTHER`, 메모 생략·빈칸·공백만 입력은 `null`이다. 메모는 최대 100자다. 수정에서 생략한 필드는 유지하고 `memo: null`은 삭제한다. 금액·날짜·카테고리의 명시적 `null`은 400이다. 동일 날짜·금액·카테고리의 중복 기록은 허용한다.
- 예산은 현재 월만 PUT할 수 있고 과거 월은 조회만 한다. 미래 월 예산은 저장하지 않는다. `(user_id, budget_month)`의 단일 upsert로 동시 정상 PUT도 한 행만 유지한다. 경합을 이유로 앱에 재시도용 409를 주지 않는다.
- 지출 목록은 날짜 DESC, ID DESC로 정렬한다. `limit` 기본값은 20, 최대 50이다. 첫 요청에는 커서가 없고 마지막은 `nextCursor: null`이다. 커서는 발급된 사용자·월에서만 유효하며 형식 오류 또는 다른 월의 커서는 400 `INVALID_CURSOR`다. 월 변경이나 지출 변경 뒤 앱은 첫 페이지부터 다시 조회한다.

### 월 계산과 통계

- 월 경계는 `Asia/Seoul`의 `[월 첫날, 다음 달 첫날)`이다. `spentAmount`는 사용자·월의 현재 지출 합계, `budgetBalanceAmount = budgetAmount - spentAmount`는 부호가 있는 잔액이다.
- 사용률은 `spentAmount / budgetAmount × 100`이다. API `usagePercent`는 소수 첫째 자리에서 버리고 펫 상태는 표시 전 실제 비율로 판정한다. 현재 월의 상태는 50% 미만 `STABLE`, 50% 이상 80% 미만 `CAUTION`, 80% 이상 100% 미만 `WARNING`, 100% 이상 `BUDGET_REACHED`다. 100% 초과도 같은 상태이며 잔액은 음수다.
- 통계 응답은 `month`, `budgetAmount`, `spentAmount`, `expenseCount`, `usagePercent`, `budgetBalanceAmount`, `categories`다. 각 카테고리는 `category`, `spentAmount`, `expenseCount`, `sharePercent`다. 지출이 있는 카테고리만 금액 내림차순으로 반환하고 동률은 고정 코드 순서다. 비중은 소수 첫째 자리 반올림이다.
- 지출이 없는 월은 합계 0·건수 0·빈 카테고리 배열이다. 예산 없는 과거 월은 `budgetAmount`, `usagePercent`, `budgetBalanceAmount`만 `null`이다. 지난달을 포함한 과거 월에는 펫을 반환하지 않는다.
- 지출이나 현재 월 예산이 바뀌면 홈·통계를 DB 원본 기준으로 다시 계산한다. 지출 날짜를 다른 월로 옮기면 이전 월과 새 월 모두 갱신한다. 월 합계 캐시는 정확한 DB 구현·성능 측정 뒤 별도 실험으로 다룬다.

### 공통 실패 응답

공통 실패 응답은 `code`, `message`를 가진다. 입력 오류에는 해당 필드 정보를 더한다. 잘못된 입력·커서는 400, 미인증·무효 토큰은 401, 없거나 다른 사용자의 지출 ID는 구분 없이 404다. 홈의 온보딩 미완료는 위의 409 코드다. 사용자 데이터 조회와 변경은 인증된 사용자 범위로 제한한다. 인증 오류 코드는 `INVALID_REQUEST`(400), `SOCIAL_LOGIN_FAILED`(401), `UNAUTHORIZED`(401), `SOCIAL_LOGIN_UNAVAILABLE`(503)이다.

## 4. 스프린트별 API 수와 데이터

| 스프린트 | 추가 동작 | 고유 경로 증가 | 대상 기능 | 스키마 작업 |
|---:|---:|---:|---|---|
| 0 | 5 | 4 | `F-M01` | 빈 DB에 3개 논리 테이블의 Flyway 기준선 생성 |
| 1 | 6 | 4 | `F-M02`, `F-M03` | `expenses` 사용·목록 인덱스 검증 |
| 2 | 3 | 2 | `F-M04`, `F-M06` | 월 예산 upsert·유일성 검증 |
| 3 | 1 | 1 | `F-M05` | 집계 쿼리와 날짜 범위 검증 |
| **합계** | **15** | **11** | `F-M01`~`F-M06` | **3개 논리 테이블** |

스프린트 수량은 구현 계획이며 코드에 해당 API가 이미 존재한다는 뜻이 아니다. 앱 화면의 정보 순서·빈 상태·과거 월 표시 규칙은 `03-user-flow.md`를 따른다.
