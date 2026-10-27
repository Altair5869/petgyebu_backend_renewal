# 2026-10-27 월 합계 집계 이동 전후 비교

> **after p95가 before p95의 2배를 넘은 시나리오가 있다: heavy home (12.92 → 26.71ms, 2.07배).** 같은 조건으로 두 번 더 재측정해도 24.62ms, 25.55ms로 재현됐다(아래 표). 나머지 시나리오는 2배 이내다.

원본 출력: `2026-10-27-monthly-spending/explain/`(EXPLAIN), `2026-10-27-monthly-spending/k6/`(k6 summary JSON, `setup_data` 제거)

## 환경

- before: `8b0c431` (develop). 합계를 SQL `SUM`·`GROUP BY`(sumAmount, sumByCategory)로 계산
- after: `16a771e` (refactor/service-rules). `SELECT category, amount` 한 번으로 행을 가져와 `MonthlySpending`에서 합산
- 장비·소프트웨어는 2026-10-25 기준선과 같다: Apple M2, 16GB, Docker Desktop(8 CPU·8GB), Java 25, PostgreSQL 16(tmpfs), k6, 앱 기본 설정. 앱·DB·k6를 한 대에서 실행
- 한 세션에서 같은 컨테이너·같은 시드 DB(사용자 1,000명 × 12개월, 지출 468,000건)로 측정했다. 시드는 한 번만 했고 사이에 다시 하지 않았다. 앱은 포트 8090에서 before, after 순서로 하나씩 실행했다.
- create·mixed는 지출을 늘리므로 돌리지 않았다. 따라서 이 문서의 모든 측정은 시드 그대로의 데이터다.
- 시나리오 실행 순서는 before 5개 → after 5개 → after heavy home 재측정 2회다.

## DB 실행 계획 (Execution Time ms, 이번 달 / 6달 전)

uid 60: normal(월 30건), uid 1: mid(월 100건), uid 100: heavy(월 300건).

| 쿼리 | normal | mid | heavy |
| --- | ---: | ---: | ---: |
| before sumAmount | 0.094 / 0.095 | 0.184 / 0.178 | 0.327 / 0.296 |
| before sumByCategory | 0.077 / 0.071 | 0.079 / 0.097 | 0.165 / 0.158 |
| after findAmounts | 0.114 / 0.091 | 0.182 / 0.158 | 0.298 / 0.300 |

- 홈은 before에서 sumAmount, after에서 findAmounts를 쓴다. 통계는 before에서 sumAmount + sumByCategory 두 쿼리, after에서 findAmounts 한 쿼리다. EXPLAIN의 `findAmounts`가 읽는 행 수는 월 지출 건수와 같다.
- DB 쿼리 시간은 모든 경우 1ms 미만이다. 출력 행 수만큼 클라이언트로 전달되는 비용은 Execution Time에 포함되지 않는다.

## HTTP 부하 (`k6/baseline.js`, VU 20, 측정 30초, ms)

| 시나리오 | 구분 | avg | p50 | p95 | p99 | max | p95 after/before |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| normal home | before | 9.05 | 8.73 | 13.62 | 17.03 | 29.51 | |
| | after | 9.14 | 8.81 | 13.97 | 17.79 | 42.56 | 1.03 |
| normal stats_current | before | 13.15 | 12.91 | 18.03 | 21.70 | 33.58 | |
| | after | 7.85 | 7.56 | 11.90 | 15.23 | 32.70 | 0.66 |
| normal stats_past | before | 13.06 | 12.78 | 17.88 | 21.90 | 60.83 | |
| | after | 7.88 | 7.56 | 12.08 | 15.67 | 34.36 | 0.68 |
| heavy home | before | 8.70 | 8.42 | 12.92 | 16.11 | 30.80 | |
| | after | 15.77 | 14.52 | 26.71 | 34.78 | 128.46 | 2.07 |
| heavy stats_current | before | 12.92 | 12.71 | 16.93 | 20.27 | 52.27 | |
| | after | 14.26 | 13.34 | 23.45 | 29.87 | 51.44 | 1.39 |

heavy home after 재측정(같은 DB·같은 앱 실행 방식, 앱은 재시작):

| 구분 | avg | p50 | p95 | p99 | max |
| --- | ---: | ---: | ---: | ---: | ---: |
| after 재측정 1 | 14.52 | 13.39 | 24.62 | 31.88 | 62.14 |
| after 재측정 2 | 15.08 | 13.94 | 25.55 | 33.03 | 78.73 |

- 모든 시나리오 실패율 0%.
- normal은 사용자 50명(uid 10–59), heavy는 10명(uid 100, 200, …, 1000)의 토큰을 VU가 나눠 쓴다.
- 같은 장비에서 k6·앱·DB가 CPU를 나눠 쓰므로 실행마다 값이 흔들린다. 2026-10-25 기준선의 heavy home(avg 13.15, p95 20.30)과 이번 before(avg 8.70, p95 12.92)도 차이가 크다. 그래서 before/after 비교는 이번 세션의 쌍만 쓴다.

## 해석

- 통계(normal): 쿼리 두 개가 한 개로 줄어든 after의 avg가 약 40% 낮고(13.15 → 7.85, 13.06 → 7.88) p95도 낮다(18.03 → 11.90, 17.88 → 12.08).
- 홈(normal)은 거의 같다(p95 13.62 → 13.97). 이 시나리오의 쿼리는 둘 다 한 개이고 행은 30건이다.
- heavy는 after가 느리다. 홈은 avg 8.70 → 15.77, p95 12.92 → 26.71로 2.07배이고, 통계는 avg 12.92 → 14.26, p95 16.93 → 23.45(1.39배)다. heavy 홈의 재측정 두 번도 p95 24.62, 25.55로 같은 방향이다.
- 같은 heavy 월 300건에서 DB Execution Time은 before sumAmount 0.327/0.296ms, after findAmounts 0.298/0.300ms로 비슷하다. 따라서 DB 쿼리 실행 시간만으로는 heavy 홈의 차이가 설명되지 않는다. 이 측정은 차이의 원인(행 전송·매핑, JVM 처리 등)을 분리하지 않았다.
- normal 통계는 빨라졌지만 heavy 홈은 2배를 넘게 느려졌으므로, 집계를 서비스로 옮긴 것의 이득은 월 지출 건수에 따라 다르다. 이 측정은 시드 데이터 한 규모(normal 30건, heavy 300건)와 한 장비에서만 이뤄졌다.
