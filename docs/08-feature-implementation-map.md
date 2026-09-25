# 기능 구현 맵: Petgyebu MVP

- 기준일: 2026-09-25
- API 접두사: `/api/v1`; 아래 경로는 접두사를 포함한다.
- 총 **12개 API**, **3개 테이블**. 기능 ID는 `02-requirements-features.md`와 같다.

## API 목록

| # | 기능 | 메서드 | 경로 | 요청·응답 핵심 | 테이블 |
|---:|---|---|---|---|---|
| 1 | F-M01 | POST | `/api/v1/auth/login/{provider}` | `provider=kakao|apple`, 인가 코드 → 액세스 토큰·신규 여부 | `users` |
| 2 | F-M01 | GET | `/api/v1/users/me` | 공급자, 사용자 ID, 반려동물 종류 | `users` |
| 3 | F-M01 | DELETE | `/api/v1/users/me` | 사용자와 종속 지출·예산 삭제 → 204 | 3개 |
| 4 | F-M02 | PUT | `/api/v1/users/me/pet` | `petType=CAT|DOG` → 선택 종류 | `users` |
| 5 | F-M03 | POST | `/api/v1/expenses` | `amount`, `expenseDate`, `category`, `memo?` → 생성 ID | `expenses` |
| 6 | F-M03 | GET | `/api/v1/expenses?month=YYYY-MM` | 지정 월 목록, 날짜·ID 역순 | `expenses` |
| 7 | F-M03 | PATCH | `/api/v1/expenses/{id}` | 전달 필드만 수정 → 현재 지출 | `expenses` |
| 8 | F-M03 | DELETE | `/api/v1/expenses/{id}` | 본인 지출 삭제 → 204 | `expenses` |
| 9 | F-M04 | PUT | `/api/v1/budgets/{month}` | `amount` → 해당 월 예산 생성 또는 교체 | `monthly_budgets` |
| 10 | F-M04 | GET | `/api/v1/budgets/{month}` | `budget: null` 또는 금액·총지출·사용률·잔여/초과 | 2개 |
| 11 | F-M05 | GET | `/api/v1/stats/{month}` | 총액·건수·카테고리별 합계·건수·비중 | `expenses` |
| 12 | F-M06 | GET | `/api/v1/home/{month}` | 예산 요약, 상태, 반려동물 종류·성장 | 3개 |

## 응답·오류 규칙

- 월 경계는 `Asia/Seoul`의 해당 월 1일부터 다음 달 1일 전까지다. `expenseDate`가 속한 월로 집계한다.
- 예산이 없으면 사용률·잔여/초과는 `null`이고 상태는 `NO_BUDGET`이다. 반려동물 미선택은 `petType: null`로 반환한다.
- 잔여 금액은 `max(budget - spent, 0)`, 초과 금액은 `max(spent - budget, 0)`이다. 사용률은 소수 첫째 자리 표시지만 상태 판정은 원래 비율로 한다.
- 미인증 401, 입력 오류 400, 다른 사용자의 지출 ID나 없는 지출 ID는 동일하게 404로 응답한다. 탈퇴 후 남은 토큰도 401이다.
- 지출 입력 중복은 허용한다. PUT 예산의 동시 생성은 `(user_id, budget_month)` UNIQUE로 하나만 남긴다. 경합으로 생성이 충돌하면 409를 반환하며 클라이언트가 PUT을 다시 보낼 수 있다. `SELECT FOR UPDATE`는 사용하지 않는다.

## 스프린트별 수량

| 스프린트 | API 수 | 새 테이블 |
|---|---:|---|
| 0 | 3 | `users` |
| 1 | 5 | `expenses` |
| 2 | 3 | `monthly_budgets` |
| 3 | 1 | 없음 |
| **합계** | **12** | **3개** |

Sprint 1의 API 5개는 반려동물 선택 1개와 지출 4개다. Sprint 2의 API 3개는 예산 2개와 홈 1개다. 상세 필드·경계는 `02-requirements-features.md`, 스키마는 `09-db-design.md`를 따른다.
