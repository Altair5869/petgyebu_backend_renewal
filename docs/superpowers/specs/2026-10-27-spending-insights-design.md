# 소비 인사이트(지난주 비교·예산 소진 예측) 설계

- 작성: 2026-10-27
- 기준: [리뉴얼 개발 기준](../../00-renewal-development-guide.md) 4·8절, [Service 규칙 구조](2026-10-27-service-rules-refactor-design.md)
- 범위: 백엔드 API만. 앱 화면은 후속 작업이다.

## 1. 의도

지금 통계는 달이 끝난 뒤 돌아보기에는 좋지만, 달 중간에 행동을 바꿀 시점을 알려주지 않는다. 사용자는 "이번 주는 카페를 줄여야겠다"처럼 **이번 달을 지내는 도중에 바로 판단**하고 싶어 한다(사용자 발언). 이를 위해 카테고리별로 이번 주와 지난주 같은 기간을 비교하고, 지금 속도로 예산을 언제 다 쓰는지 예측해 내려준다.

사용자 결정:

- 비교 대상은 **지난주**다.
- **같은 요일 범위끼리** 비교한다. 오늘이 수요일이면 이번 주 월~수와 지난주 월~수를 비교한다.
- 월 1~3일차에는 예측을 숨긴다(4일차부터 보여준다).
- 이번에는 **백엔드 API까지만** 만든다.
- 기존 홈·통계 응답을 바꾸지 않고 **새 엔드포인트**로 낸다.

## 2. 목표·비목표·실패 조건

목표

- 현재(서울 기준 오늘) 카테고리별 이번 주 vs 지난주 같은 요일 범위 금액과 차이.
- 현재 월 하루 평균 지출과 예산 소진 예상일.

비목표

- 여러 달 추세, 가게·장소 단위 분석, 요일·시간대 분석.
- 푸시·알림(MVP 범위 밖), 앱 화면, 저장되는 집계·캐시.
- 기존 API 15개의 요청·응답 변경, DB 스키마 변경.

실패 조건

- 달 초반 지출 한 번으로 "곧 소진" 같은 과한 예측이 나간다(1~3일차 숨김이 지켜지지 않음).
- 진행 중인 이번 주와 지난주 7일 전체를 비교해 이번 주가 늘 적어 보인다.
- 다른 사용자의 지출이 섞인다.
- 서울과 UTC 날짜가 다른 시간대(서울 00:00~08:59)에 오늘·월·주가 UTC 기준으로 계산된다.

## 3. API

`GET /api/v1/insights` — 인증 필요(없거나 잘못되면 기존과 같은 401 `UNAUTHORIZED`). 요청 파라미터 없음. 다른 오류 응답은 없다.

- **펫 선택을 요구하지 않는다.** 예산이 없어도 409를 내지 않는다(제 판단: 주간 비교는 펫·예산 없이도 의미가 있다).

```json
{
  "today": "2026-10-29",
  "weekly": {
    "thisWeek": {"from": "2026-10-27", "to": "2026-10-29"},
    "lastWeek": {"from": "2026-10-20", "to": "2026-10-22"},
    "categories": [
      {"category": "CAFE", "thisWeekAmount": 21000, "lastWeekAmount": 8000, "changeAmount": 13000}
    ]
  },
  "forecast": {
    "status": "WILL_EXHAUST",
    "dailyAverageAmount": 12500,
    "exhaustionDate": "2026-11-03"
  }
}
```

### 3.1 `weekly`

- 주는 서울 기준 **월요일 시작**이다. `thisWeek` = 이번 주 월요일 ~ 오늘, `lastWeek` = 각각 7일 전(같은 요일 범위). 월요일이면 두 범위 모두 하루다. 날짜는 양 끝 포함.
- 주는 **달력 주**다. 달을 걸쳐도 끊지 않는다(제 판단: "이번 주"라는 말에 맞다).
- 지출의 `category`는 NOT NULL이며 7개 값(`FOOD, CAFE, TRANSPORT, SHOPPING, HEALTH, LIVING, OTHER`) 중 하나다. 미분류 버킷은 없다. `expense_date`는 `DATE`라 시각 경계가 없고 오늘 날짜 지출은 포함한다.
- `categories`: 두 범위 중 어느 한쪽에라도 지출이 있는 카테고리만. `changeAmount = thisWeekAmount - lastWeekAmount`(음수 가능). 정렬은 `changeAmount` 내림차순, 동률은 `Category` 선언 순서(제 판단: 늘어난 카테고리가 맨 위에 와야 "줄일 대상"이 먼저 보인다). 지출이 없으면 빈 배열.

### 3.2 `forecast`

서울 기준 현재 월, `d` = 오늘의 일(day of month), `spent` = 현재 월 1일 ~ 오늘 지출 합계(현재 월 전체 합계와 같다. 미래 날짜 지출은 저장되지 않는다), `budget` = 현재 월 예산.

상태는 아래 순서로 처음 맞는 것 하나다(제 판단: 이미 다 쓴 사실은 달 초반에도 숨기지 않는다).

| 순서 | 조건 | `status` | `dailyAverageAmount` | `exhaustionDate` |
|---|---|---|---|---|
| 1 | 현재 월 예산 없음 | `NO_BUDGET` | `null` | `null` |
| 2 | `spent >= budget` | `BUDGET_REACHED` | `null` | `null` |
| 3 | `d <= 3` | `TOO_EARLY` | `null` | `null` |
| 4 | `spent == 0` | `WITHIN_BUDGET` | `0` | `null` |
| 5 | `n = ceil(budget × d / spent)`가 그 달 일수 이하 | `WILL_EXHAUST` | `floor(spent / d)` | 그 달 `n`일 |
| 6 | 그 외 | `WITHIN_BUDGET` | `floor(spent / d)` | `null` |

- `n`은 하루 평균 `spent / d`로 누적했을 때 처음 예산 이상이 되는 날이다. 반올림 오차를 피하려고 정수 연산(올림 나눗셈)으로 계산한다. `spent < budget`이므로 `n > d`, 즉 내일 이후다.
- 정확히 그 달 마지막 날에 예산에 닿으면 `WILL_EXHAUST`(마지막 날)이다.
- `BUDGET_REACHED`는 홈의 펫 상태 `BUDGET_REACHED`(사용률 100% 이상)와 같은 경계다.
- `spent`는 같은 시점 홈의 `spentAmount`와 항상 같은 값이다(둘 다 현재 월 지출 행의 합계이며 캐시·저장 집계는 없다). 예산을 달 중간에 바꾸면 다음 요청부터 새 예산으로 계산한다.
- 금액은 모두 `long`이다. 지출 한 건과 예산은 1억 원 이하라 `budget × d ≤ 1억 × 31`로 `long` 범위 안이다.

## 4. 구조

[Service 규칙 구조](2026-10-27-service-rules-refactor-design.md)를 따른다: DB는 행 조회만, 계산은 도메인 객체, Service에는 이름 붙은 규칙.

- 조회: 사용자의 `[min(현재 월 1일, 지난주 월요일), 오늘]` 범위 지출 행 `(expense_date, category, amount)`을 한 번 조회하고 주간 비교와 월 합계를 모두 여기서 계산한다(한 스냅숏). 범위는 최대 약 6주다. 예산은 기존 `MonthlyBudgetRepository.find`로 읽는다.
- 계산은 DB 없이 단위 테스트할 수 있는 도메인 객체(예: 주간 비교, 예측)로 둔다. 시간은 주입된 `Clock`과 `Asia/Seoul`.
- 새 패키지 `insight`(컨트롤러·서비스·도메인 객체). 인덱스 `ix_expenses_user_date_id`를 그대로 쓰며 마이그레이션은 없다.

## 5. 검증

- 단위 테스트(도메인 객체): 월요일(하루 범위), 수요일, 일요일; 달을 걸친 주; 한쪽에만 있는 카테고리, 음수 차이, 동률 정렬; 예측 표의 각 행; 3일차 `TOO_EARLY`·4일차 계산; 마지막 날 정확히 소진; 정확히 `spent == budget`; 26억 원대 합계(오버플로 없음).
- API 테스트(Testcontainers): 응답 필드 집합, 다른 사용자 지출 제외, 예산 없음 `NO_BUDGET`, 펫 없이 200, 미인증 401, 서울 경계(UTC 전날 15:00~ = 서울 다음날)에서 `today`·주·월 계산.
- `./gradlew test --no-daemon` 전체 통과, PR 전 qa-engineer 검토.

## 6. 문서 갱신

- `docs/00-renewal-development-guide.md` 8절 API 표에 16번째 동작 추가(문구 "15개 동작, 11개 고유 경로" 갱신), 응답 필드·상태 규칙 요약.
- `docs/02-requirements-features.md`에 기능 항목, `docs/08-feature-implementation-map.md`·`docs/10-task-backlog.md`에 새 백엔드 항목(번호는 빌더가 기존 체계에 맞춘다).
- 앱 화면 후속 작업을 백로그에 미완료로 남긴다.

## 7. 빌더에게 맡기는 것

- 클래스·메서드·record 이름, 응답 record 구성.
- 조회 메서드 이름과 반환 record.
- 백로그·기능 ID 번호.
