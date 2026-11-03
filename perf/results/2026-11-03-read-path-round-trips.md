# 2026-11-03 조회 경로 DB 왕복 줄이기 전후 비교

> **DB 왕복 약 2ms 조건에서 p95 중앙값(3회)이 home normal 19.06 → 14.26ms(25.2% 감소), heavy 19.91 → 16.39ms(17.7%), stats_current normal 14.96 → 12.47ms(16.6%), heavy 17.76 → 15.50ms(12.7%)로 낮아졌다.** 요청당 DB 문장은 home 5 → 3, stats_current 4 → 3이다. 실패율은 모두 0%다.

배경: 캐시 이외의 성능 점검(2026-11-03)에서 제안한 2번(조회 API의 읽기 전용 트랜잭션 제거)과 3번(홈의 사용자·예산 조회 합치기)을 사용자가 승인했다. [세션 확인 트랜잭션 제거](2026-11-02-session-check.md)와 같은 방식으로 측정했다.
원본 출력: `2026-11-03-read-path-round-trips/statements/`(요청당 DB 문장), `latency/`(지연 확인, 측정 시작·끝), `k6/`(k6 summary JSON, `setup_data` 제거)

## 변경

- 조회 메서드 7곳에서 `@Transactional(readOnly = true)`를 지웠다: `HomeService.home`, `StatsService.stats`·`dailyTotals`, `InsightService.insights`, `ExpenseService.get`·`list`, `CategoryGoalService.get`. 쓰기 트랜잭션은 그대로다.
  - `UserService.me`는 그대로 두었다. 이 메서드가 부르는 `UserRepository.findById`는 `@Query` 없는 기본 메서드라 Spring Data JDBC의 `SimpleJdbcRepository`(클래스에 `@Transactional(readOnly = true)`)로 실행된다. 서비스 애너테이션을 지워도 트랜잭션이 남아 왕복이 줄지 않는다.
  - PostgreSQL 기본 격리 수준(READ COMMITTED)에서는 트랜잭션 안에서도 문장마다 새 스냅숏을 본다. 그래서 여러 SELECT를 읽기 전용 트랜잭션으로 묶어도 문장 사이의 일관성은 생기지 않았고 `BEGIN`/`COMMIT` 왕복만 더해졌다. 조회가 하나뿐인 메서드는 그 조회의 스냅숏을 그대로 쓴다.
- 홈은 `users.findById`와 `budgets.find`를 `UserRepository.findPetAndBudget` 한 쿼리(`users LEFT JOIN monthly_budgets ON user_id, budget_month`)로 바꿨다. 오류 순서(행 없음 401 → `PET_REQUIRED` → `BUDGET_REQUIRED`)와 Service의 월 합계 계산(`findAmounts` → `MonthlySpending`)은 같다.
- 테스트 추가(`HomeApiTests.otherUsersCurrentBudgetDoesNotSatisfyCurrentMonth`): 다른 사용자에게만 이번 달 예산이 있으면 `BUDGET_REQUIRED`다(JOIN 조건에서 `user_id`가 빠지면 실패). 나머지 경계는 기존 홈 테스트가 덮는다.

## 환경

- 장비·도구·데이터는 [캐시 실험](2026-11-02-cache-experiment.md)과 같다(Apple M2, 측정 DB tmpfs, toxiproxy 2.12.0, k6 v2.3.0, 사용자 200명 × 12개월, VU 10, 워밍업 10초 + 측정 30초).
- 변경 전: develop `ab7e4a3`(worktree). 변경 후: `46ff62f`. 둘 다 포트 8090에서 하나씩 띄웠다(동시에 띄우지 않음).
- 지연: toxic `set 1`. `select 1` 5회가 측정 시작 1.85–2.72ms(중앙값 2.20), 끝 1.19–2.39ms(중앙값 1.98)였다.
- 순서: r1, r2, r3 모두 변경 전 → 변경 후(라운드마다 앱을 다시 띄움). 각 앱에서 normal home, heavy home, normal stats_current, heavy stats_current 순서.
- 장비 부하(load average 1분값)는 라운드 시작 때 6.6, 16.9, 9.8이었다. 앞선 시도(같은 날 저녁)는 디스크가 가득 차(여유 2.6GB) 앱 시작이 실패하고 값이 20–1,000ms로 흔들려 버렸다. 공간을 확보한(여유 18GB) 뒤 처음부터 다시 쟀다.

## 요청당 DB 문장 (지연 2ms, normal, 워밍업 1초 + 5초)

| 엔드포인트 | 구분 | 인증 요청 수(`SELECT EXISTS`) | 요청당 문장 |
| --- | --- | ---: | --- |
| home | 변경 전 | 4,180 | 5: `BEGIN READ ONLY`, 세션 확인, `users` 전체 행, `monthly_budgets`, `findAmounts` |
| | 변경 후 | 6,338 | 3: 세션 확인, 펫·예산 JOIN, `findAmounts` |
| stats_current | 변경 전 | 5,138 | 4: `BEGIN READ ONLY`, 세션 확인, `monthly_budgets`, `findAmounts` |
| | 변경 후 | 6,339 | 3: 세션 확인, `monthly_budgets`, `findAmounts` |

`BEGIN`(50회)은 k6 setup 로그인 트랜잭션이다. `COMMIT`은 이전 측정처럼 pg_stat_statements에 거의 잡히지 않아(0–8회) 직접 세지 못했다. 드라이버가 `BEGIN`을 첫 조회와 같이 보내므로, 줄어든 왕복은 home `COMMIT` 1 + 조회 1 = 약 2개, stats `COMMIT` 1개로 추정한다.

## HTTP 부하 (2ms, `http_req_duration{phase:measure}`, ms)

| 시나리오 | 구분 | p95 r1 / r2 / r3 | **p95 중앙값** | p50 중앙값 | 요청 수(워밍업 포함) r1 / r2 / r3 |
| --- | --- | --- | ---: | ---: | --- |
| normal home | 변경 전 | 18.88 / 19.06 / 19.17 | **19.06** | 14.14 | 27,879 / 27,614 / 27,666 |
| | 변경 후 | 29.65 / 14.19 / 14.26 | **14.26** | 10.15 | 27,783 / 38,974 / 39,195 |
| heavy home | 변경 전 | 17.98 / 19.91 / 20.02 | **19.91** | 15.43 | 27,558 / 25,692 / 25,602 |
| | 변경 후 | 19.11 / 16.39 / 16.06 | **16.39** | 11.76 | 26,228 / 34,340 / 35,070 |
| normal stats_current | 변경 전 | 14.09 / 14.96 / 15.45 | **14.96** | 10.58 | 37,631 / 36,213 / 35,396 |
| | 변경 후 | 11.67 / 12.47 / 12.52 | **12.47** | 8.89 | 46,276 / 43,684 / 43,154 |
| heavy stats_current | 변경 전 | 14.85 / 17.76 / 20.00 | **17.76** | 13.61 | 34,023 / 29,481 / 26,486 |
| | 변경 후 | 15.00 / 17.42 / 15.50 | **15.50** | 11.89 | 36,609 / 31,679 / 32,661 |

- p95 중앙값 감소: home normal 25.2%, heavy 17.7%, stats_current normal 16.6%, heavy 12.7%.
- p50 중앙값 감소: home 4.0ms(normal)·3.7ms(heavy), stats_current 1.7ms(normal 1.69)·1.7ms(heavy 1.72). 추정으로 줄어든 왕복(home 약 2개, stats 1개, 왕복 약 2ms)의 크기와 맞는다.
- r1의 변경 후 home(normal 29.65, heavy 19.11ms)과 heavy stats_current(15.00ms, 변경 전 14.85)는 변경 전보다 높았다. r2 시작 때 장비 부하가 16.9로 가장 높았으므로 r1 후반에 다른 프로세스와 CPU를 다툰 것으로 보이나 원인은 분리하지 않았다. r2·r3에서는 8개 비교 모두 변경 후가 p95·p50 모두 낮았다.
- heavy stats_current는 개선이 작고 흔들림이 크다. 같은 라운드끼리 p95 차이는 +0.15 / −0.34 / −4.50ms로, 12.7% 감소는 주로 r3에서 나왔다. p50은 세 라운드 모두 낮아졌다(−0.33 / −1.05 / −3.22ms).
- 매 라운드 변경 전을 먼저 쟀으므로 시간대 영향이 한쪽으로 기울 수 있다. 판정은 3회 중앙값으로 했다.
- 이번 세션의 절대값은 이전 세션(같은 2ms 조건 home p95 19–42ms)과 다르다. 비교는 이번 세션의 전후 쌍만 쓴다.

## 확인하지 않은 것

- 인사이트, 달력, 지출 단건·목록, 카테고리 목표 조회의 부하 측정. 같은 방식으로 `BEGIN READ ONLY`/`COMMIT`이 빠지므로 요청당 왕복 1개가 준다고 추정한다.
- 실제 Cloud Run·Supabase 환경.
