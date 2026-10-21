# Petgyebu 리뉴얼 개발 기준

- 갱신: 2026-10-10
- 대상: `petgyebu_backend_renewal` 백엔드와 연결되는 앱
- 상태: **확정된 MVP 제품·데이터·API 계약의 우선 기준**. 후속 결정은 11절에 명시한다.
- 근거: [2026 매형즈 스터디 회의록](https://app.notion.com/p/3eaa117053d780c5b032fbe193c6a9cb?source=copy_link), [사용자 흐름 FigJam](https://www.figma.com/board/viSG5enjuib5thvwwIUY0m), [8개 화면 와이어프레임](https://www.figma.com/design/Ttkey5th2ALEig6hI1IWrz), 이후 사용자와 확정한 결정

## 1. 문서 적용 순서

1. Claude Code는 리뉴얼 작업 전에 이 문서를 읽는다. `docs/01-*.md`~`docs/10-*.md`는 이 기준에 맞춰 재수정했다. 세부 문서가 충돌하면 **이 문서가 우선**한다.
2. `sources/`는 이전 프로젝트의 읽기 전용 자료다. 성장·레벨, 선택적 예산, 70% 상태 경계, Apple 로그인, 미래 지출 허용, 과거 월 펫 표시, 기존 12개 API 경로를 현행 요구사항으로 가져오지 않는다.
3. 이전 검토 기록인 `docs/04-review-log.md`는 확정된 계약의 근거로 사용하지 않으며 최종 개발 문서 묶음에서도 제외한다.
4. 계약을 변경할 때 관련 문서를 함께 갱신하고 이 기준과 대조한다. 미확정 사항은 추측해 확정하지 않는다.

## 2. 제품 목표와 범위

**핵심 가치:** 사용자가 펫의 현재 상태를 보고 예산 대비 소비를 직관적으로 이해한다. 펫 상태는 현재 월에만 존재한다.

- 사용자가 직접 선택해 입력한 지출만 합산한다. 자동 금융 연동·거래 수집·수입·자동 분류·푸시·보상·상점·아이템은 MVP에서 제외한다.
- CAT 또는 DOG 한 마리를 선택한다. 펫 성장·레벨·경험치와 별도 펫 테이블은 MVP에서 제외한다.
- 카카오·구글 로그인만 제공한다. 같은 사람이 각 공급자로 로그인하면 계정은 별개이며 자동 통합하지 않는다.
- 최초 진입은 **로그인 → 펫 선택 → 현재 월 예산 설정 → 현재 월 홈**이다. 펫 선택과 현재 월 예산은 홈 진입 전 필수다.
- 새 달에는 직전 달 예산을 입력란에 미리 보여준다. 사용자가 확인하거나 변경해 저장하기 전에는 새 달 예산을 자동 생성하지 않는다.
- 성공 판단의 우선 기준은 사용자가 숫자를 보지 않고 펫 상태의 의미를 이해하는지다. 상태 갱신을 이해하고 계속 기록하는지도 본다. 목표 수치는 아직 정하지 않았다.

## 3. 화면과 사용자 흐름

### 현재 월과 과거 월

- 현재 홈의 정보 순서는 **펫 상태 → 예산 잔액·사용률 → 지출 기록**이다. 지출이 0건이면 안정 상태와 첫 기록 안내를 보여준다. `GET /api/v1/home`은 현재 월 전용이다.
- 지난달을 포함한 과거 월은 지출 합계·예산·사용률·카테고리 등 **숫자만** 보여준다. 과거 펫 이미지·표정·상태는 표시하지 않는다.
- 월 선택기에는 현재 월과 모든 과거 월을 제공한다. 지출이 없는 달도 선택할 수 있으며 0원·0건으로 표시한다. 미래 월은 선택할 수 없다.
- 과거 월에 예산이 없어도 지출 총액·건수·카테고리 통계는 보여준다. 예산·사용률·예산 잔액은 미설정으로 표시한다. 통계 API는 예산이 없는 현재 월도 409 없이 같은 방식으로 응답한다.
- 예산은 **현재 월에만** 설정·수정한다. 과거 예산은 조회만 가능하고 미래 월 예산은 미리 저장하지 않는다. 과거 지출의 조회·수정·삭제는 허용한다.

### 상세 통계와 지출 내역

- 상세 통계 순서는 `월 선택 → 지출 총액·건수 → 카테고리별 금액·비중 → 전체 지출 내역`이다. 최대 7개 카테고리는 일반 세로 스크롤을 사용한다.
- 카테고리는 지출이 있는 항목만 금액 내림차순으로 보여준다. 동률이면 고정 코드 순서 `FOOD, CAFE, TRANSPORT, SHOPPING, HEALTH, LIVING, OTHER`를 사용한다.
- 지출 목록은 무한 스크롤이다. 날짜 내림차순, 같은 날짜에서는 ID 내림차순으로 정렬한다. 초기·추가 로딩, 빈 목록, 재시도, 마지막 페이지를 구분한다.
- 메모가 있으면 목록 제목은 메모, 보조 정보는 날짜·카테고리다. 메모가 없으면 카테고리 이름을 제목으로, 날짜를 보조 정보로 보여준다. 금액은 항상 표시한다. 긴 제목은 목록에서 한 줄로 줄이고 수정 화면에서 전체를 볼 수 있다.
- 지출 수정은 입력 화면을 재사용해 기존 값을 미리 채운다. 수정 화면에만 삭제 동작을 두고 삭제 전에 확인한다. 수정 후에는 **수정된 날짜가 속한 월의 목록**으로 이동한다.
- 지출 생성·수정·삭제 후 관련 월의 목록·통계를 다시 조회한다. 날짜가 다른 월로 이동하면 이전 월과 새 월을 모두 갱신한다. 현재 월이 영향을 받으면 펫 상태도 갱신한다.

## 4. 금액 계산과 펫 상태

월 경계는 `Asia/Seoul`의 `[월 첫날, 다음 달 첫날)`이다. 사용자와 월에 속한 현재 지출 레코드의 금액 합계가 `spentAmount`다. 예산은 1원 이상 1억 원(100,000,000) 이하의 원화 정수다.

- `budgetBalanceAmount = budgetAmount - spentAmount`. 양수면 남은 금액, 0이면 예산 도달, 음수면 절댓값만큼 초과다. 예산이 없는 월의 통계(현재 월 포함)에서는 `null`이다.
- 실제 사용률은 `spentAmount / budgetAmount × 100`이다. **상태 판정은 표시값으로 반올림·절삭하기 전의 원래 비율**로 한다.
- API의 `usagePercent`는 소수 첫째 자리에서 **버림**한 값이다. 앱은 소수부가 0이면 `.0`을 숨긴다. 따라서 실제 49.99%는 49.9%·안정, 정확히 50%는 50%·주의다.

| 실제 사용률 | `petState` | 펫 연출 |
|---|---|---|
| 0% 이상 50% 미만 | `STABLE` | 편안한 자세와 표정 |
| 50% 이상 80% 미만 | `CAUTION` | 눈이 커지고 남은 예산에 집중 |
| 80% 이상 100% 미만 | `WARNING` | 시선이 사용자와 예산 사이를 오가고 앞발이 조금 안절부절 |
| 100% 이상 | `BUDGET_REACHED` | 움직임을 멈추고 빈 예산 게이지를 놀란 눈으로 바라봄 |

- 정확히 50%, 80%, 100%는 각각 새 구간에 속한다. 30%는 별도 상태 경계가 아니다.
- 100% 초과도 다섯 번째 펫 상태를 만들지 않는다. `budgetBalanceAmount < 0`으로 초과 문구와 절댓값을 표시한다.
- 정확히 100%는 “예산을 모두 사용했어요” 방향이다. 표정은 ‘벌써 다 썼어요…’라는 인상을 주되 실제 문구에 ‘벌써’를 강제하지 않는다. 비난·질병·처벌의 이미지와 문구를 피한다.
- 예산을 현재 월 중간에 수정하면 합계는 그대로 두고 사용률·잔액·펫 상태를 새 예산으로 즉시 다시 계산한다.
- 펫 종류를 변경하면 현재 월 홈의 펫 종류만 바뀐다. 지출·예산과 과거 월의 숫자는 그대로다.

## 5. 지출 입력과 카테고리 통계

| 항목 | 규칙 |
|---|---|
| `amount` | 필수, 원화 정수, 1원 이상 1억 원(100,000,000) 이하 |
| `expenseDate` | 앱 입력칸은 오늘로 미리 채움. API 요청에는 필수. `Asia/Seoul` 기준 2000-01-01부터 오늘까지만 허용 |
| `category` | `FOOD, CAFE, TRANSPORT, SHOPPING, HEALTH, LIVING, OTHER`. 생성 시 생략하거나 `null`이면 `OTHER` |
| `memo` | 선택. DB `TEXT`, 앱·서버 최대 100자(유니코드 코드 포인트). NUL 문자(U+0000)는 400. 생성·수정 시 빈칸·공백만 있으면 `null`, 생성 시 생략도 `null` |
| 중복 | 같은 날짜·금액·카테고리의 여러 지출 허용 |
| 취소·환불 | 전체 취소는 지출 삭제, 일부 환불은 금액 수정. 별도 환불 거래·변경 이력은 MVP 제외 |

`PATCH`에서는 보내지 않은 필드를 유지한다. `memo: null`은 메모 삭제다. `amount`, `expenseDate`, `category`를 명시적으로 `null`로 보내면 입력 오류다. 생성의 `OTHER` 기본값은 수정에는 적용하지 않는다.

카테고리 비중 `sharePercent = 카테고리 지출 / 월 지출 총액 × 100`은 **소수 첫째 자리 반올림**이다. 표시값의 합이 99.9% 또는 100.1%여도 실제 금액을 조정하지 않는다. 월 지출이 0건이면 총액 0원·건수 0건, 카테고리 배열은 비운다.

## 6. 계정과 인증

- 카카오·구글 로그인 성공 시 서버가 자체 액세스·리프레시 토큰을 발급한다. 앱을 닫았다 열어도 로그인을 유지한다.
- **한 계정에는 한 활성 로그인만** 둔다. 새 로그인은 이전 로그인과 토큰을 무효화한다. 액세스 토큰은 15분, 리프레시 토큰은 마지막 사용 후 30일 동안 유효하다. 30일 사용하지 않으면 소셜 로그인을 다시 한다.
- 토큰 갱신마다 새 액세스·리프레시 토큰을 발급하고 이전 리프레시 토큰은 무효화한다. 앱은 새 토큰으로 교체한다. 같은 리프레시 토큰으로는 갱신이 한 번만 성공하므로 앱은 갱신 요청을 한 번에 하나만 보내고, 진행 중인 갱신 결과를 다른 요청이 함께 사용한다.
- `active_session_id`는 **이번 로그인**의 식별자다. 앱 설치나 휴대폰 식별자인 `device_id`와 다르다. 액세스 토큰의 세션 ID를 현재 사용자 값과 비교해 새 로그인·로그아웃 후 이전 액세스 토큰도 거부한다.
- `users`에는 `last_login_time`, `active_session_id`, `refresh_token_hash`, `refresh_token_expires_at`을 둔다. 원본 리프레시 토큰은 DB에 저장하지 않는다. `last_login_time`은 소셜 로그인 성공 시 갱신한다.
- `device_id`는 MVP 테이블에 추가하지 않는다. 기기 관리·푸시의 목적과 식별 방식이 정해질 때 재검토한다.
- 로그아웃 요청은 현재 리프레시 토큰으로 서버의 활성 로그인을 지우고 204로 응답한다. 앱도 보관한 토큰을 지운다.
- 탈퇴 시 지출 → 월 예산 → 사용자 순으로 **한 DB 트랜잭션에서 명시적으로 삭제**한다. DB `ON DELETE CASCADE`는 사용하지 않는다. 탈퇴 후 남은 토큰은 거부한다. 탈퇴와 겹친 지출 생성·예산 PUT이 사용자 FK 위반으로 실패하면 `401 UNAUTHORIZED`다. 탈퇴 중 같은 사용자의 행이 끼어들어 FK 위반이 나면 삭제 트랜잭션을 처음부터 다시 실행한다(최대 3회, 모두 실패하면 `500 INTERNAL_ERROR`).

## 7. 데이터 모델과 제약

논리 테이블은 **`users`, `expenses`, `monthly_budgets` 세 개**다. 펫 상태·월 합계·사용률을 영구 컬럼이나 별도 테이블에 저장하지 않는다.

| 테이블 | 확정된 주요 필드와 근거 |
|---|---|
| `users` | `id`, `provider`(KAKAO/GOOGLE), `provider_user_id`(공급자가 해당 사용자에게 부여한 ID), `pet_type`(온보딩 전 NULL), `created_at`, `last_login_time`, `active_session_id`, `refresh_token_hash`, `refresh_token_expires_at`. 별도의 `provider_id`는 `provider_user_id`와 같은 뜻이므로 두지 않는다. |
| `expenses` | `id`, `user_id`, `amount`, `expense_date`, `category`, `memo`(`TEXT`), `created_at`, `updated_at`. `amount`는 이 테이블에서 지출액을 뜻한다. `idempotency_key`(`UUID`, NULL 허용)는 `(user_id, idempotency_key)`가 NULL이 아닐 때만 유일하다. |
| `monthly_budgets` | `id`, `user_id`, `budget_month`(연도 포함, 해당 월 1일의 `DATE`), `amount`, `created_at`, `updated_at`. `amount`는 이 테이블에서 월 예산을 뜻한다. |

- `expenses.user_id`와 `monthly_budgets.user_id`는 DB FK를 유지하되 `ON DELETE CASCADE`를 두지 않는다.
- `UNIQUE(provider, provider_user_id)`와 `UNIQUE(user_id, budget_month)`를 둔다. 동일 사용자·월 예산 PUT의 동시 요청은 단일 upsert로 한 행만 유지한다.
- 지출 목록을 위해 `(user_id, expense_date DESC, id DESC)` 인덱스를 둔다. 내용이 같은 지출은 막지 않으며, 지출의 UNIQUE는 재시도 중복 방지용 부분 인덱스 `(user_id, idempotency_key)`(키가 NULL이 아닐 때만)뿐이다.
- 필수 필드의 `NOT NULL`, 합의한 FK·UNIQUE는 DB에 둔다. **금액 양수, 카테고리 7개, `budget_month`의 월 첫날 정규화 같은 업무 규칙은 애플리케이션에서 검증**하며 DB `CHECK`는 두지 않는다. API를 거치지 않는 직접 DB 변경은 이 검증을 우회하므로 마이그레이션·운영 절차에서 별도 확인한다.
- `budget_month`는 단순한 월 번호가 아니다. API의 `YYYY-MM`을 서버가 해당 월 1일로 변환해 저장한다. 날짜·금액·카테고리 검증과 탈퇴 트랜잭션은 테스트한다.

## 8. API 경로와 응답 계약

접두사는 `/api/v1`, `{month}`는 `YYYY-MM`이다. **15개 동작, 11개 고유 경로**다. 로그인 요청의 credential은 카카오 SDK 액세스 토큰·구글 ID 토큰이다(스토어 앱 기준).

| # | 메서드 | 경로 | 확정된 용도 |
|---:|---|---|---|
| 1 | POST | `/api/v1/auth/login/{provider}` | 카카오·구글 로그인 |
| 2 | POST | `/api/v1/auth/refresh` | 리프레시 토큰 교체 및 새 토큰 발급 |
| 3 | POST | `/api/v1/auth/logout` | 현재 로그인 무효화 |
| 4 | GET | `/api/v1/users/me` | `id, provider, petType` 조회. `provider`는 `KAKAO\|GOOGLE`, `petType`은 `CAT\|DOG`, 선택 전 `petType: null` |
| 5 | DELETE | `/api/v1/users/me` | 회원 탈퇴 |
| 6 | PUT | `/api/v1/users/me/pet` | CAT/DOG 선택·변경, 갱신된 내 정보 반환. `petType` 생략·`null`·그 외 값은 400 `INVALID_REQUEST` |
| 7 | GET | `/api/v1/home` | 현재 월 펫·금액 요약 |
| 8 | GET | `/api/v1/months/{month}/budget` | 해당 월 예산 조회, 미설정 시 `amount: null`. `{month}`는 지출 목록과 같이 2000-01부터 서울 기준 현재 월까지의 `YYYY-MM`만 허용하고 그 외는 400 `INVALID_REQUEST`(`month`) |
| 9 | PUT | `/api/v1/months/{month}/budget` | **현재 월만** 예산 생성·변경. 과거·미래 월과 형식 오류는 400 `INVALID_REQUEST`(`month`) |
| 10 | POST | `/api/v1/expenses` | 지출 생성. 선택 헤더 `Idempotency-Key`(UUID)가 있으면 같은 사용자·키·내용의 재시도는 새로 만들지 않고 200과 기존 지출을 돌려준다 |
| 11 | GET | `/api/v1/months/{month}/expenses` | 지출 커서 목록 |
| 12 | GET | `/api/v1/expenses/{expenseId}` | 지출 단건 조회·수정 초기값 |
| 13 | PATCH | `/api/v1/expenses/{expenseId}` | 지출 일부 수정, 날짜의 월 이동 가능 |
| 14 | DELETE | `/api/v1/expenses/{expenseId}` | 지출 삭제 |
| 15 | GET | `/api/v1/months/{month}/stats` | 현재·과거 월 숫자 통계 |

### 공통 응답 필드

- 지출 생성·단건 조회·수정과 목록의 각 `item`은 `id, amount, expenseDate, category, memo`를 같은 형식으로 반환한다. 메모가 없으면 `memo: null`이다. DB의 `created_at`·`updated_at`은 화면 응답에 넣지 않는다.
- 예산 GET/PUT은 `{month, amount}`를 반환한다. PUT 요청은 `{amount}`다. 미설정 과거 월은 `amount: null`이다.
- 홈은 `month, petType, petState, budgetAmount, spentAmount, usagePercent, budgetBalanceAmount`를 반환한다. 카테고리·지출 목록은 포함하지 않는다.
- 통계는 `month, budgetAmount, spentAmount, expenseCount, usagePercent, budgetBalanceAmount, categories`를 반환한다. 각 카테고리는 `category, spentAmount, expenseCount, sharePercent`를 가진다. **펫 필드는 통계에 없다.** 예산이 없는 월은 과거·현재 모두 200으로 `budgetAmount, usagePercent, budgetBalanceAmount`만 `null`이다.
- 로그인과 토큰 갱신 응답은 `accessToken, refreshToken, accessExpiresInSeconds`를 반환한다. 액세스 만료 초는 `900`이다. 갱신 요청은 현재 `refreshToken`을 보낸다. 로그아웃도 현재 리프레시 토큰으로 요청한다.
- 로그인 요청 본문은 `{credential}`이다. `credential`은 4,096자 이하이며 넘으면 공급자 호출 전에 `400 INVALID_REQUEST`(`credential`)이다. `credential`은 카카오는 SDK가 발급한 액세스 토큰, 구글은 Google Sign-In의 ID 토큰이다. 서버는 카카오 토큰 정보 API(`app_id` 일치)와 구글 JWKS(서명·`iss`·`aud`·`exp`)로 확인한다. 토큰이 틀리면 `401 SOCIAL_LOGIN_FAILED`, 공급자 장애·응답 이상·해당 공급자 설정 누락이면 `503 SOCIAL_LOGIN_UNAVAILABLE`이다. `{provider}`는 `kakao`·`google`이며 대소문자를 구분하지 않는다. 로그인·갱신·로그아웃 세 경로는 `Authorization` 헤더가 있어도 무시하므로 만료된 액세스 토큰으로도 갱신·로그아웃할 수 있다.

### 지출 목록과 오류

- `GET /months/{month}/expenses`의 `limit` 기본값은 20, 최대 50이다. 첫 요청에는 `cursor`가 없다. 응답은 `items`와 `nextCursor`이며 마지막 페이지는 `nextCursor: null`이다.
- 커서는 앱이 해석하지 않는 이어보기 표식이며 발급받은 사용자·월에만 유효하다. 월을 바꾸거나 지출을 생성·수정·삭제하면 앱은 커서를 버리고 첫 페이지부터 다시 조회한다. 잘못된 형식이나 다른 사용자·월의 커서는 `400 INVALID_CURSOR`다. `limit`이 1~50 밖이면 보정하지 않고 `400 INVALID_REQUEST`다. `{month}`가 미래 월이거나 2000-01 이전이면 `400 INVALID_REQUEST`다.
- 앱은 내 정보와 현재 월 예산을 확인한 뒤 홈으로 이동한다. 설정 전 홈 호출은 `409 PET_REQUIRED` 또는 `409 BUDGET_REQUIRED`다. 둘 다 빠졌다면 펫 선택을 먼저 안내한다.
- 공통 오류 응답은 `code`와 `message`를 가진다. 입력 오류에는 해당 필드 정보를 추가한다. 잘못된 입력은 400, 로그인 필요·무효 토큰은 401, 없거나 다른 사용자의 지출 ID는 구분 없이 `404 EXPENSE_NOT_FOUND`다. 프레임워크 단계 오류도 같은 형식이다: 없는 경로 `404 NOT_FOUND`(인증 후), 지원하지 않는 메서드 `405 METHOD_NOT_ALLOWED`(`Allow` 헤더 포함), 지원하지 않는 Content-Type `415 UNSUPPORTED_MEDIA_TYPE`, 처리되지 않은 서버 오류 `500 INTERNAL_ERROR`(내부 오류 내용은 응답에 넣지 않는다).
- `POST /expenses`의 `Idempotency-Key` 헤더는 선택이며 없으면 매번 새 지출을 만든다. 값은 표준 36자 UUID(대소문자 무관)이며 형식이 틀리면 `400 INVALID_REQUEST`(`Idempotency-Key`)다. 본문 검증이 키 조회보다 먼저다. 같은 사용자·같은 키에 정규화 후 `amount`·`expenseDate`·`category`·`memo`가 같으면 `200`과 기존 지출, 본문이 다르면 `409 IDEMPOTENCY_KEY_REUSED`다. 지출을 삭제하면 그 키로 다시 `201`로 만들 수 있다.
- 인증 관련 오류 코드: 입력 오류 `400 INVALID_REQUEST`(`fields` 포함), 공급자 확인 실패 `401 SOCIAL_LOGIN_FAILED`, 토큰 없음·무효·만료·종료된 로그인 `401 UNAUTHORIZED`(사유 구분 없음), 공급자 장애·응답 이상·설정 누락 `503 SOCIAL_LOGIN_UNAVAILABLE`.
- 성공 응답은 지출 생성 201(같은 `Idempotency-Key` 재시도는 200), 조회·수정 200, 지출 삭제·로그아웃·탈퇴 204다. 예산 PUT은 생성·변경 모두 200이다.

## 9. 캐시 실험

1. PostgreSQL의 `expenses`를 원본으로 두고, 사용자·월 날짜 범위의 합계를 **DB 조회만으로 먼저 정확하게 구현**한다. 홈·통계가 같은 계산 규칙을 쓴다.
2. 실제 조회 성능을 측정한 뒤 **월별 지출 합계만** 캐시하는 실험을 한다. 홈 응답 전체나 펫 상태는 캐시하지 않는다.
3. 지출 생성·수정·삭제의 DB 변경이 확정된 뒤 영향받은 사용자·월의 캐시를 무효화한다. 날짜가 월을 넘으면 두 월 모두 무효화한다. 예산 변경은 월 지출 합계 캐시를 지울 필요가 없다.
4. 캐시 장애 시 DB 조회로 동작한다. 성능 개선과 동시 변경 시 최신성을 검증한 후 출시 적용 여부를 결정한다. 캐시는 출시 필수 조건이 아니다. 제품·TTL·키 포맷은 실험 단계에서 정한다.
5. 저장된 월 집계 테이블, 스냅샷, 비동기 이벤트 집계, 월 전환 배치는 현재 MVP에 포함하지 않는다.

## 10. 수용 기준

1. 신규 사용자는 펫과 현재 월 예산을 확정해야 홈에 들어간다. 새 달에는 이전 예산이 제안되지만 확인 전에는 저장되지 않는다.
2. 예산 100,000원일 때 지출 합계 0원·30,000원은 `STABLE`, 50,000원은 `CAUTION`, 80,000원은 `WARNING`, 100,000원·110,000원은 `BUDGET_REACHED`다. 110,000원의 `budgetBalanceAmount`는 -10,000원이다.
3. 실제 사용률 49.99%는 49.9%·안정으로 표시한다. 정확히 50%·80%·100%의 상태가 경계 정의와 일치한다.
4. 지출의 생성·수정·삭제와 현재 월 예산 변경 뒤 홈·통계 값이 일치한다. 날짜를 월 사이로 이동하면 두 월이 갱신된다. 과거 월에는 펫이 없다.
5. 메모 삭제 후 목록에는 카테고리가 제목으로 보인다. 카테고리 미선택 지출 생성은 `OTHER`로 저장되며 지출 수정의 `category: null`은 거부된다.
6. 커서 목록은 날짜·ID 역순으로 이어지고 마지막 페이지에서 `nextCursor: null`을 반환한다. 다른 사용자의 지출 접근과 잘못된 커서를 거부한다.
7. 새 기기 로그인·로그아웃·탈퇴 후 이전 액세스·리프레시 토큰이 거부된다. 탈퇴 도중 오류가 나면 사용자·지출·예산 삭제가 함께 롤백된다.
8. 캐시 실험 시 사용자·월 값이 섞이지 않고 무효화·장애 상황에서도 DB 원본과 일치한다.

## 11. 후속 결정

| 항목 | 지금 남겨두는 이유 |
|---|---|
| 앱인토스 로그인 | 앱인토스 미니앱은 토스 로그인만 허용한다(2026-10-16 확인, [토스 로그인 소개](https://developers-apps-in-toss.toss.im/login/intro.html)). 앱인토스 출시 때 공급자 TOSS(인가 코드 교환·mTLS·연결 끊기 콜백)를 별도로 설계한다. 카카오·구글은 스토어 앱용이다. |
| 캐시의 출시 적용과 구현 제품 | DB 기준 성능·동시성 측정 및 캐시 실험 결과를 보고 결정한다. |

위 두 항목 이외에 암호학적 토큰 형식, 커서 인코딩, 통계의 동률 처리 구현 등은 이 문서의 **관찰 가능한 계약을 지키는 범위에서** 상세 기술 문서와 구현 단계에서 정한다.
