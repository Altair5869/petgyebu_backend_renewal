# 2026-11-02 세션 확인 트랜잭션 제거 전후 비교

> **요청당 `BEGIN READ ONLY`가 2회에서 1회로 줄었고, DB 왕복 약 2ms에서 home p95(3회 중앙값)가 normal 21.46 → 19.61ms(8.6% 감소), heavy 22.78 → 18.94ms(16.9% 감소)로 낮아졌다.** 실패율은 모두 0%다.

배경: [월 합계 캐시 실험](2026-11-02-cache-experiment.md)의 제안 1. 사용자가 보안 중심 설계(최소 변경, 거부 경로·fail closed 테스트 고정)로 승인했다.
원본 출력: `2026-11-02-session-check/statements/`(요청당 DB 문장), `2026-11-02-session-check/latency/2ms.txt`(지연 확인), `2026-11-02-session-check/k6/`(k6 summary JSON, `setup_data` 제거)

## 변경

- `AuthService.isActiveSession`에서 `@Transactional(readOnly = true)`만 지웠다. 쿼리(`SELECT EXISTS ... active_session_id`)·조건·호출 위치(`SessionCheckingJwtConverter`)는 같다.
- 한 문장 조회는 트랜잭션이 없어도(자동 커밋) 커밋된 최신 세션을 보므로, 로그아웃·다른 기기 로그인·탈퇴 뒤 이전 토큰 거부는 그대로다.
- 테스트 추가(`AuthApiTests`): 유효 서명이지만 `sid`가 없거나 활성 세션이 아닌 토큰 401, 세션 확인 중 DB 오류 시 컨트롤러에 도달하지 않음(fail closed). 로그아웃·재로그인·탈퇴 뒤 거부는 기존 테스트(`AuthApiTests.logoutRevokesTokens`, `newLoginInvalidatesPreviousTokens`, `UserApiTests.withdrawalRejectsRemainingTokens`)가 이미 덮는다.

## 환경

- 장비·도구·데이터는 [캐시 실험](2026-11-02-cache-experiment.md)과 같다(Apple M2, 측정 DB tmpfs, toxiproxy 2.12.0, k6 v2.3.0, 사용자 200명 × 12개월, VU 10, 워밍업 10초 + 측정 30초).
- 변경 전: develop `737015e`(worktree, 포트 8090). 변경 후: `0f00f5f`(포트 8091). 두 앱을 같은 측정 DB·프록시에 동시에 연결했다.
- 지연: toxic `set 1`, `select 1` 5회 1.87–2.78ms(중앙값 1.96ms).
- 순서: r1 전→후, r2 후→전, r3 전→후로 번갈아 쟀다(시간대 영향을 나누기 위해). 각 r에서 normal, heavy 순서.

## 요청당 DB 문장 (지연 0, normal, 워밍업 1초 + 5초)

| 엔드포인트 | 구분 | 인증 요청 수(`SELECT EXISTS`) | `BEGIN READ ONLY` | 비율 |
| --- | --- | ---: | ---: | ---: |
| home | 변경 전 | 9,141 | 18,282 | 2 |
| | 변경 후 | 10,728 | 10,728 | 1 |
| stats_current | 변경 전 | 11,540 | 23,080 | 2 |
| | 변경 후 | 13,514 | 13,514 | 1 |

`BEGIN`(50회)은 k6 setup 로그인 트랜잭션이다. `COMMIT`은 pg_stat_statements에 거의 잡히지 않아(양쪽 모두 0–8회) 직접 세지 못했다. 그래서 트랜잭션 하나가 줄면 `COMMIT` 왕복도 하나 준다고 `BEGIN READ ONLY` 감소에서 추정했고, 추정 왕복은 home 약 6 → 5, stats 약 5 → 4다.

## HTTP 부하 (home, 2ms, `http_req_duration{phase:measure}`, ms)

| 시나리오 | 구분 | p95 r1 / r2 / r3 | **p95 중앙값** | p50 중앙값 | 요청 수(워밍업 포함) r1 / r2 / r3 |
| --- | --- | --- | ---: | ---: | --- |
| normal | 변경 전 | 26.34 / 20.26 / 21.46 | **21.46** | 16.09 | 22,235 / 24,789 / 24,260 |
| | 변경 후 | 20.46 / 19.61 / 19.13 | **19.61** | 13.98 | 27,536 / 27,960 / 27,883 |
| heavy | 변경 전 | 23.61 / 21.33 / 22.78 | **22.78** | 17.83 | 21,692 / 22,973 / 22,182 |
| | 변경 후 | 18.94 / 18.90 / 19.24 | **18.94** | 14.34 | 26,925 / 26,921 / 26,046 |

- p95 감소: normal 8.6%, heavy 16.9%. p50 감소: normal 2.1ms, heavy 3.5ms.
- 세 번 모두 변경 후가 p95·p50 모두 낮았다(r2는 변경 후를 먼저 쟀다).
- 없어진 것은 (위 추정대로) `COMMIT` 왕복 1개(약 2ms)다. p50 감소(2.1–3.5ms)는 그 크기와 비슷하거나 조금 크다. 큰 쪽은 처리량이 늘며 대기가 준 효과로 보이나 원인은 분리하지 않았다.
- 이번 세션의 절대값은 캐시 실험 세션(같은 2ms 조건 home p95 27.89–42.00)보다 낮다. 실행 시간대에 따라 이 장비의 값이 크게 흔들리므로 비교는 이번 세션의 전후 쌍만 쓴다.

## 확인하지 않은 것

- stats와 다른 엔드포인트의 부하 측정(문장 수 감소는 stats_current에서도 확인).
- 실제 Cloud Run·Supabase 환경.
- 세션 확인 중 DB 오류 시 응답 본문: 실제 서버는 500이지만 공통 에러 형식(`{code, message}`)이 아니라 Spring Boot 기본 형식이다. 이번 변경 전부터 그랬고, 요청은 통과하지 않는다(fail closed). 형식 통일은 별도 결정이다.
