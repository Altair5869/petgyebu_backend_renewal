# 작업 백로그: Petgyebu 리뉴얼 MVP

- 갱신: 2026-10-11
- 기준: [리뉴얼 개발 기준](00-renewal-development-guide.md), [스프린트 계획](06-sprint-plan.md), [구현 맵](08-feature-implementation-map.md), [DB 설계](09-db-design.md)
- 범위: `petgyebu_backend_renewal` 백엔드와 별도 React Native 앱. 백엔드 `M-001`~`M-010`, 앱 `APP-001`~`APP-005`를 유지한다.
- 상태: 아래 체크는 **구현 작업**의 완료 여부다. 문서 수정이나 이전 제품의 완료 기록만으로 체크하지 않는다. 현재 코드와 앱의 구현·검증 증거가 없는 항목은 `[ ]`로 둔다.

## 공통 완료 기준

1. 작업에 해당하는 [기능 요구사항](02-requirements-features.md), [사용자 흐름](03-user-flow.md), API·DB 계약을 만족한다. 문서가 충돌하면 `00-renewal-development-guide.md`를 우선한다.
2. 백엔드 작업은 정상 응답, 입력 오류, 사용자별 접근, 서울 시간대 월 경계와 필요한 동시성 사례를 테스트한다. 스키마 변경은 빈 PostgreSQL의 Flyway 마이그레이션으로 검증한다. `./gradlew test --no-daemon`을 통과한다.
3. 앱 작업은 iOS·Android에서 필수 설정, 빈 상태, 로딩·재시도, 로그인 만료, 현재·과거 월 이동을 해당 범위에 맞춰 확인한다. 앱 저장소의 검증 결과를 기록한다.
4. 새 작업 브랜치는 `develop`에서 분기하고 검증한 PR을 `develop`에 반영한다. 완료 표시는 관련 PR 또는 재현 가능한 검증 증거를 남긴 뒤 갱신한다. 앱과 백엔드가 한 흐름을 이루면 서로의 작업을 연결한다.

## Sprint 0 — 기반·계정·세션

| ID | 작업과 완료 기준 | 의존 | 상태 |
|---|---|---|---|
| `M-001` | **백엔드 기반 확인.** 현재 Java 25·Spring Boot 4.1.1·Gradle·Spring MVC·Spring Data JDBC·PostgreSQL·Flyway 구성과 `application.yaml`을 `05-infra-stack.md`와 대조하고 기본 테스트 통과를 기록한다. | — | [x] |
| `APP-001` | **앱 기반 생성.** 별도 React Native 앱 프로젝트에서 iOS·Android 기본 실행과 백엔드 API 주소 설정을 확인한다. 앱 작업의 PR·검증은 앱 저장소에 기록한다. | — | [ ] |
| `M-002` | **세 테이블 Flyway 기준선.** 빈 PostgreSQL에 `users`, `expenses`, `monthly_budgets`를 만들고 확정된 필드, NOT NULL·FK·두 UNIQUE·지출 목록 인덱스를 검증한다. DB `CHECK`와 `ON DELETE CASCADE`, 이전 제품 마이그레이션은 넣지 않는다. | `M-001` | [x] |
| `M-003` | **카카오·구글 로그인과 세션.** `(provider, provider_user_id)`로 계정을 식별한다. 자체 액세스·리프레시 토큰을 발급하고 15분·마지막 사용 후 30일 만료, 갱신 시 토큰 교체, 한 활성 로그인, 로그아웃을 검증한다. 이전 액세스·리프레시 토큰과 잘못된 인증은 거부하고 `last_login_time`·세션 ID·토큰 해시·만료를 확인한다. 로그인 credential은 카카오 SDK 액세스 토큰·구글 ID 토큰이며, 앱인토스용 토스 로그인은 후속 작업이다. | `M-002` | [ ] |
| `M-004` | **내 정보·탈퇴.** 선택 전 `petType: null`인 내 정보를 반환한다. 탈퇴는 지출 → 예산 → 사용자 순으로 한 트랜잭션에서 삭제한다. 탈퇴 뒤 토큰은 거부되고 삭제 중 실패하면 모든 행이 롤백된다. | `M-003` | [x] |
| `APP-002` | **로그인·계정 화면.** 카카오·구글 로그인, 앱 재시작 후 로그인 유지, 토큰 갱신·만료 안내, 새 로그인으로 이전 세션 종료, 내 정보, 로그아웃·탈퇴 확인을 iOS·Android에서 검증한다. | `APP-001`, `M-003`, `M-004` | [ ] |

> `M-001` 검증 기록 (2026-10-11): `build.gradle`의 Java 25·Spring Boot 4.1.1·Gradle Wrapper 9.7.1, Spring Web MVC·Data JDBC·PostgreSQL·Flyway·Testcontainers 의존성과 `application.yaml`의 앱 이름을 `05-infra-stack.md`와 대조했다. 로컬 Java 25.0.4.1·Docker 28.5.1에서 `./gradlew test --no-daemon --rerun-tasks` 통과(테스트 1개, 실패 0개). 현재 설정 파일은 앱 이름만 담고 있으며 DB 접속은 테스트의 `@ServiceConnection`으로 제공한다. 운영 DB·토큰·소셜 비밀값 설정은 해당 기능·배포 작업에서 환경 변수로 연결한다.

> `M-002` 검증 기록 (2026-10-13): `V1__baseline.sql`이 빈 PostgreSQL 16(Testcontainers)에 `users`, `expenses`, `monthly_budgets`만 만든다. `FlywayBaselineSchemaTests`로 컬럼 타입(VARCHAR 길이 포함)·NULL 허용, 복합 `UNIQUE(provider, provider_user_id)`·`UNIQUE(user_id, budget_month)`(같은 공급자의 다른 ID, 다른 사용자의 같은 월은 허용), 없는 사용자 FK 거부, 사용자 삭제 시 CASCADE 없음, DB `CHECK` 없음, 중복 지출 허용, `(user_id, expense_date DESC, id DESC)` 인덱스를 검증했다. `./gradlew test --no-daemon` 통과(테스트 10개, 실패 0개).

> `M-003` 진행 기록 (2026-10-13): 로그인·갱신·로그아웃 API, HS256 액세스 토큰(15분, 세션 ID 포함), 해시 저장 리프레시 토큰(마지막 사용 후 30일, 갱신 시 교체), 한 활성 로그인, Bearer 인증 필터와 공통 오류 응답을 구현했다. 공급자 확인은 `SocialIdentityVerifier` 뒤에 있으며 운영 구현은 연동 전까지 `503 SOCIAL_LOGIN_UNAVAILABLE`을 반환한다. 테스트는 가짜 verifier로 검증했다. `./gradlew test --no-daemon` 통과(테스트 42개, 실패 0개). 실제 카카오·구글 연동 전이므로 완료 표시하지 않는다.

> `M-003` 후속 기록 (2026-10-16): 운영 확인기를 카카오 토큰 정보 API(app_id 일치)·구글 JWKS 검증으로 교체하고 공급자 확인을 로그인 DB 트랜잭션 밖으로 옮겼다. 가짜 공급자 서버로 정상·토큰 오류(401)·공급자 장애(503)·설정 누락(503)을 검증했다. 실제 개발자 콘솔 키로 앱에서 확인하는 일은 M-010에 남아 있어 완료 표시하지 않는다.

> `M-004` 검증 기록 (2026-10-14): `GET /api/v1/users/me`가 `{id, provider, petType}`를 반환한다(`provider`는 저장값 `KAKAO`·`GOOGLE`, 선택 전 `petType: null`). `DELETE /api/v1/users/me`는 지출 → 월 예산 → 사용자 순의 명시적 DELETE를 한 트랜잭션에서 실행하고 204를 반환한다. `UserApiTests`로 미인증 401, 다른 사용자 행 보존, 탈퇴 뒤 액세스·리프레시 토큰 401, 사용자 삭제 단계를 PostgreSQL 트리거로 실패시켰을 때 지출·예산·사용자 행 전체 롤백을 검증했다. 실제 카카오·구글 연동 전이므로 `M-003`과 같은 가짜 verifier로 로그인한 상태에서 검증했다. `./gradlew test --no-daemon` 통과(테스트 48개, 실패 0개). 후속(2026-10-14): 탈퇴와 겹친 쓰기의 FK 위반 처리(쓰기 401, 탈퇴 삭제 최대 3회 재시도)를 추가하고 `UserApiTests`·`ExpenseApiTests`·`BudgetApiTests`·`DbErrorsTests`로 검증했다.

## Sprint 1 — 펫 선택·수동 지출

| ID | 작업과 완료 기준 | 의존 | 상태 |
|---|---|---|---|
| `M-005` | **지출 API와 커서 목록.** 생성·월 목록·단건 조회·부분 수정·삭제를 구현한다. 양의 원화 정수, 서울 시간 기준 오늘·과거 날짜, 생성의 `OTHER` 기본값, 메모 100자와 `memo: null` 삭제를 검증한다. 다른 사용자의 지출 ID는 404, 명시적 필수값 `null`·미래 날짜는 400이다. 중복 입력을 허용한다. 목록은 날짜·ID 역순, 기본 20·최대 50건, `items`·`nextCursor`이고 잘못된·다른 월 커서는 `INVALID_CURSOR`다. | `M-002`, `M-003` | [x] |
| `M-006` | **펫 선택·변경.** `CAT`·`DOG` 한 종류를 `users.pet_type`에 저장하고 선택 전 `null`을 허용한다. 변경해도 지출·예산은 그대로다. 별도 펫 테이블, 성장·레벨 계산은 만들지 않는다. | `M-003` | [x] |
| `APP-003` | **펫·지출 화면.** 펫 선택·변경, 지출 입력·월 목록·단건 수정·삭제 확인을 연결한다. 날짜 기본값은 오늘이고 메모가 없으면 카테고리가 목록 제목이다. 빈 목록, 첫·추가 로딩, 재시도·마지막 페이지를 구분한다. 월 변경·지출 변경 때 커서를 버리고, 날짜 수정 뒤 새 날짜의 월 목록으로 이동한다. | `APP-002`, `M-005`, `M-006` | [ ] |

> `M-005` 검증 기록 (2026-10-14): `POST /api/v1/expenses`(201), `GET /api/v1/months/{month}/expenses`(`{items, nextCursor}`), `GET`·`PATCH /api/v1/expenses/{expenseId}`(200), `DELETE`(204)를 구현했다. 응답 항목은 `{id, amount, expenseDate, category, memo}`다. `ExpenseApiTests`로 1원 이상 정수 금액, 서울 기준 오늘·과거 날짜(UTC 전날인 서울 00:30 포함), 생성의 `OTHER` 기본값, 메모 생략·공백 → `null`, 메모 100자(코드 포인트) 경계, `PATCH`의 생략 유지·`memo: null` 삭제·필수 필드 명시적 `null` 400·월 이동, 중복 허용, 다른 사용자·없는 지출 ID의 같은 404 `EXPENSE_NOT_FOUND`, 미인증 401을 검증했다. 목록은 같은 날짜 동률을 포함한 날짜·ID 역순 다중 페이지, 정확히 `limit`만큼 남은 마지막 페이지의 `nextCursor: null`, 기본 20·최대 50(0·51·숫자 아님은 400), 빈·과거 월, 형식 오류·서울 기준 미래 월 400, 서울 월말 경계, 다른 사용자 행 미노출, 형식 오류·다른 사용자·다른 월 커서의 400 `INVALID_CURSOR`를 검증했다. 실제 카카오·구글 연동 전이므로 `M-004`와 같은 가짜 verifier로 로그인한 상태에서 검증했다. QA 후속으로 금액 1억 원 이하, 지출 날짜 2000-01-01 이후·조회 월 2000-01 이후, 메모 NUL 문자 400(기존 500), 커서 날짜가 경로 월 밖이면 `INVALID_CURSOR`(기존 극단 날짜 500)를 추가 검증했고 `PATCH`의 서울 기준 미래 날짜 거부도 확인했다. 알려진 제약: 같은 지출의 동시 `PATCH`는 잠금 없이 마지막 쓰기가 이긴다(사용자 결정으로 수용). `./gradlew cleanTest test --no-daemon` 통과(테스트 82개, 실패 0개).

> `M-006` 검증 기록 (2026-10-14): `PUT /api/v1/users/me/pet`이 `users.pet_type`만 갱신하고 `GET /api/v1/users/me`와 같은 `{id, provider, petType}`를 200으로 반환한다. `UserApiTests`로 `CAT` 선택 뒤 내 정보 반영, `CAT` → `DOG` 변경, 응답 필드 3개, 다른 사용자의 `pet_type` 유지, 변경한 사용자의 지출·예산 행 내용 불변, 생략·`null`·`"cat"`·`"BIRD"`·빈 문자열·숫자·배열 `petType`의 400 `INVALID_REQUEST`(`petType`)와 기존 값 유지, 객체가 아닌·읽을 수 없는 본문 400, 미인증 401, 사용자 행이 사라진 경우 401 `UNAUTHORIZED`를 검증했다. 실제 카카오·구글 연동 전이므로 `M-004`와 같은 가짜 verifier로 로그인한 상태에서 검증했다. `./gradlew cleanTest test --no-daemon` 통과(테스트 87개, 실패 0개).

## Sprint 2 — 필수 예산·현재 홈

| ID | 작업과 완료 기준 | 의존 | 상태 |
|---|---|---|---|
| `M-007` | **월 예산 조회·PUT.** 금액은 1원 이상 1억 원 이하다. 현재 월만 설정·변경하고 과거는 조회만, 미래는 저장하지 않는다. 미설정 월은 `{month, amount: null}`이다. 같은 사용자·월의 동시 정상 PUT은 단일 upsert로 한 행을 유지하고 생성·변경 모두 200을 반환한다. | `M-002`, `M-003` | [x] |
| `M-008` | **공통 월 합계·현재 홈.** 사용자·월 지출 합계로 잔액의 부호와 사용률을 계산한다. 사용률은 소수 첫째 자리 버림, 상태는 실제 비율의 50%·80%·100% 경계로 판정한다. 0건은 `STABLE`, 100% 초과는 `BUDGET_REACHED`와 음수 잔액이다. 펫·현재 예산이 없으면 409 `PET_REQUIRED`·`BUDGET_REQUIRED`이고 둘 다 없으면 펫을 먼저 안내한다. 홈은 현재 월 전용이다. | `M-005`, `M-006`, `M-007` | [x] |
| `APP-004` | **필수 온보딩·홈.** 로그인 → 펫 → 현재 월 예산 → 홈을 강제한다. 새 달에는 직전 예산을 제안만 하고 저장 확인 전에는 행을 만들지 않는다. 홈은 펫 상태 → 예산 잔액·사용률 → 지출 기록 순서다. 안정·주의·경고·예산 도달의 표정과 남은·초과 금액을 구분하고 직접 기록한 지출 기준 문구를 보인다. 예산·지출 변경 뒤 현재 월 상태가 갱신된다. | `APP-003`, `M-007`, `M-008` | [ ] |

> `M-007` 검증 기록 (2026-10-14): `GET /api/v1/months/{month}/budget`이 `{month, amount}`(미설정이면 `amount: null`)를, `PUT`이 `{amount}`를 받아 생성·변경 모두 200 `{month, amount}`를 반환한다. `budget_month`는 해당 월 1일로 저장하고, 저장은 `INSERT … ON CONFLICT (user_id, budget_month) DO UPDATE` 단일 upsert이며 시각은 주입된 `Clock`을 쓴다. `{month}` 해석은 지출 목록과 공유한다(`common/Months`). `BudgetApiTests`로 미설정 현재·과거 월 `null`, 저장된 과거 예산 조회, 생성 뒤 변경의 같은 행·금액 교체·`created_at` 유지·`updated_at` 갱신, 과거·미래 월 PUT 400(`month`)과 행 미생성, 형식 오류·1999-12·서울 기준 미래 월 GET/PUT 400, 서울 월말 경계(서울 11/01 00:30에 11월 PUT 허용·10월 PUT 400), 금액 1·1억 허용과 0·-1·100,000,001·1.5·`"1000"`·`null`·`true`·생략·long 초과 400(`amount`)과 기존 값 유지, 객체가 아닌·읽을 수 없는 본문 400, 다른 사용자 예산 미노출·미변경, 같은 사용자·월에 10개 스레드 동시 PUT이 모두 성공하고 제출한 금액 중 하나로 한 행만 남음, 미인증 401을 검증했다. 서버는 직전 달 예산을 이월하거나 자동 생성하지 않는다. 실제 카카오·구글 연동 전이므로 `M-004`와 같은 가짜 verifier로 로그인한 상태에서 검증했다. `./gradlew cleanTest test --no-daemon` 통과(테스트 100개, 실패 0개).

> `M-008` 검증 기록 (2026-10-14): `GET /api/v1/home`이 서울 기준 현재 월의 `{month, petType, petState, budgetAmount, spentAmount, usagePercent, budgetBalanceAmount}`를 200으로 반환한다. `spentAmount`는 `expenses`의 사용자·`[월 첫날, 다음 달 첫날)` 범위를 `COALESCE(SUM(amount), 0)` 명시적 쿼리(`ExpenseRepository.sumAmount`)로 매번 계산하고, 잔액·사용률·상태는 `budget/BudgetUsage`가 `BigDecimal`로 정확히 계산한다(`M-009`가 같은 쿼리·계산을 재사용). `usagePercent`는 소수 첫째 자리 버림 숫자(예: `49.9`, `50.0`), 상태는 버림 전 비율로 판정한다. 캐시·저장 집계는 두지 않았다. `HomeApiTests`로 예산 100,000원에 지출 0·30,000 `STABLE`, 50,000 `CAUTION`, 80,000 `WARNING`, 100,000·110,000 `BUDGET_REACHED`와 110,000의 잔액 -10,000, 예산 10,000원에 4,999(49.9·`STABLE`)·5,000·7,999(79.9·`CAUTION`)·8,000·9,999(99.9·`WARNING`)·10,000, 299,999/600,000의 49.9·`STABLE`, 0건 0.0·`STABLE`, 예산 1억에 합계 2,533,333,333원의 2533.3·음수 잔액(오버플로 없음), 펫 없음 409 `PET_REQUIRED`·현재 예산 없음 409 `BUDGET_REQUIRED`·둘 다 없음 `PET_REQUIRED`·과거 월 예산만 있음 `BUDGET_REQUIRED`·새 달 예산 미설정 `BUDGET_REQUIRED`, 서울 월 경계(전월 말일 지출 제외, 서울 11/01 00:30에 홈 월 11월·11월 예산 사용), 다른 사용자의 지출·예산 미반영, 지출 생성·금액 수정·다른 월로 날짜 이동과 복귀·삭제, 예산 PUT, 펫 변경 뒤 홈 재계산, 미인증 401, 사라진 사용자 행 401을 검증했다. 실제 카카오·구글 연동 전이므로 `M-004`와 같은 가짜 verifier로 로그인한 상태에서 검증했다. `./gradlew cleanTest test --no-daemon` 통과(테스트 117개, 실패 0개).

## Sprint 3 — 상세 통계·통합 검증

| ID | 작업과 완료 기준 | 의존 | 상태 |
|---|---|---|---|
| `M-009` | **현재·과거 월 통계.** `M-008`과 같은 DB 월 합계를 사용한다. 총액·건수와 지출이 있는 카테고리의 금액·건수·비중을 제공한다. 카테고리는 금액 내림차순·동률 고정 코드 순서, 비중은 소수 첫째 자리 반올림이다. 빈 월은 0원·0건·빈 배열, 예산 없는 과거 월은 예산 관련 필드만 `null`이며 펫 필드는 없다. 날짜 이동 전후 두 월이 갱신된다. | `M-005`, `M-007`, `M-008` | [x] |
| `APP-005` | **월별 상세 화면.** 월 선택 → 총액·건수 → 카테고리별 금액·비중 → 전체 지출 내역 순서로 구성한다. 카테고리는 일반 스크롤, 목록은 무한 스크롤이다. 현재·과거의 빈 월을 선택할 수 있고 미래 월은 제외한다. 지난달을 포함한 과거 월에는 펫이 없으며 과거 지출은 수정·삭제할 수 있다. 예산 없는 과거 월은 숫자 통계를 유지한다. | `APP-004`, `M-009` | [ ] |
| `M-010` | **MVP 통합·출시 후보 검증.** iOS·Android에서 로그인 → 펫 → 예산 → 홈 → 지출 → 상세 통계 → 로그아웃·탈퇴를 확인한다. 15개 API 동작·11개 경로·세 테이블, 사용자별 접근, 50%·80%·100% 경계, 과거 월 숫자 표시, 지출·예산 수정 뒤 정합성, 탈퇴 롤백을 검증한다. 제외 기능이 배포 대상에 없고 백엔드·앱 결과가 연결된 뒤 출시 후보로 올린다. | `APP-005`, `M-004`, `M-008`, `M-009` | [ ] |

> `M-009` 검증 기록 (2026-10-14): `GET /api/v1/months/{month}/stats`가 `{month, budgetAmount, spentAmount, expenseCount, usagePercent, budgetBalanceAmount, categories[{category, spentAmount, expenseCount, sharePercent}]}`를 200으로 반환하며 현재 월에도 펫 필드는 없다. `{month}`는 `Months.parsePastOrCurrent`로 지출 목록·예산 GET과 같이 검증한다. `spentAmount`는 홈과 같은 `ExpenseRepository.sumAmount`, 카테고리별 금액·건수는 같은 사용자·`[월 첫날, 다음 달 첫날)` 범위의 `GROUP BY category` 명시적 쿼리(`sumByCategory`)로 계산하고 `expenseCount`는 그 건수의 합이다. 두 쿼리는 `REPEATABLE READ` 읽기 전용 트랜잭션에서 같은 스냅숏을 본다. 정렬은 금액 내림차순·동률 `Category` 선언 순서, `sharePercent`는 `BigDecimal` 소수 첫째 자리 `HALF_UP`이며 합계를 보정하지 않는다. 예산 값은 그 달 예산이 있을 때만 `BudgetUsage`로 계산하고, 없으면 과거·현재 월 모두 세 값만 `null`이다(사용자 결정: 현재 월도 409 아님). 캐시·저장 집계는 두지 않았다. `StatsApiTests`로 현재·과거 월 응답 필드 집합(펫 없음), 예산 있는 과거 월·예산 없는 과거·현재 월, 빈 현재·과거 월(0원·0건·빈 배열), 금액 동률의 코드 순서, 비중 33.3/66.7·12.5/87.5·6.25→6.3(합계 100.1)·33.3×3(99.9)·0.05→0.1, 25억 원대 합계, 현재 월 값이 홈의 `budgetAmount`·`spentAmount`·`usagePercent`·`budgetBalanceAmount`와 일치, 서울 월 경계(말일·1일 귀속, 서울 11/01 00:30에 11월 조회 가능), `PATCH` 날짜 월 이동·카테고리 변경·삭제 뒤 두 월 재계산, 다른 사용자 지출·예산 제외, 잘못된 월 400, 미인증 401을 검증했다. 실제 카카오·구글 연동 전이므로 `M-004`와 같은 가짜 verifier로 로그인한 상태에서 검증했다. `./gradlew cleanTest test --no-daemon` 통과(테스트 133개, 실패 0개).

## 출시 준비와 후속 실험

| 항목 | 처리 시점·기준 |
|---|---|
| 카카오·구글 개발자 콘솔 키 발급·앱 연결 확인(M-010)과 앱인토스용 토스 로그인(후속 작업) | 실제 키로 앱에서 두 공급자 로그인을 확인한다. 토스 로그인은 앱인토스 출시 때 별도로 설계한다. 두 공급자 지원은 MVP 범위다. |
| 이용약관·개인정보처리방침, Cloud Run·Cloud SQL | 출시 준비에서 문안과 실제 배포 환경을 확인한다. 현재 백로그 체크로 배포 완료를 주장하지 않는다. |
| 월별 지출 합계 캐시 실험 | DB 원본 조회를 정확하게 구현하고 성능을 측정한 뒤 월 합계만 실험한다. 수정 후 무효화, 동시성, 장애 시 DB 대체를 검증해 출시 적용·제품을 결정한다. 출시 필수 조건이 아니다. |

계좌·자동 거래·이체·환불 거래 연결·자동 분류·거래 이력·사용자별 임계값·푸시·보상·상점·아이템·성장·월 전환 배치는 현재 MVP 작업에 포함하지 않는다. 과거 기록은 별도로 보관하며 이 백로그의 완료 상태에 영향을 주지 않는다.
