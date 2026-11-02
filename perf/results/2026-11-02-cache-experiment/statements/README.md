# 요청당 DB 문장 수 (2026-11-02, 지연 0, 사용자 200명 × 12개월)

각 파일은 시나리오 하나(k6 `WARMUP=1s`, `DURATION=5s`, VU 10)를 돌린 뒤의 `pg_stat_statements`다. 로그인 setup의 `INSERT`/`UPDATE`/`BEGIN`은 요청과 무관하다.
인증된 요청 수는 `hasActiveSession`(`SELECT EXISTS ... active_session_id`) 호출 수와 같다.

| 엔드포인트 | 요청당 조회 문장 | 요청당 `BEGIN READ ONLY` | 조회 내용 |
| --- | ---: | ---: | --- |
| home | 4 | 2 | 세션 확인, 사용자, 이번 달 예산, 월 지출 행(`findAmounts`) |
| stats_current, stats_past | 3 | 2 | 세션 확인, 그 달 예산, 월 지출 행 |

normal·heavy 모두 같은 비율이다(호출 수가 정확히 1:1:1:1, `BEGIN READ ONLY`는 2배).

- 트랜잭션은 요청마다 둘이다: 인증 필터의 `AuthService`(`@Transactional(readOnly = true)`)와 서비스(`HomeService.home`, `StatsService`).
- `COMMIT`은 `track_utility=on`인데도 거의 집계되지 않았다(home normal에서 8회). PostgreSQL JDBC 드라이버가 `BEGIN`을 첫 조회와 같은 전송에 묶어 보내고 `COMMIT`은 별도로 보내므로, 네트워크 왕복은 **트랜잭션마다 (조회 수 + COMMIT 1)**로 추정한다.
  - home: 인증(조회 1 + COMMIT) + 서비스(조회 3 + COMMIT) ≈ **6 왕복**
  - stats: 인증(조회 1 + COMMIT) + 서비스(조회 2 + COMMIT) ≈ **5 왕복**
- 월 합계 캐시가 적중하면 home에서 `findAmounts` 1개 왕복(과 그 행 전송·매핑)만 없어진다. 이 추정은 지연 0/2ms 측정의 p50 차이로 확인한다(결과 문서).
