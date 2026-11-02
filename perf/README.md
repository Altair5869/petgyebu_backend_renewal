# 성능 기준선 측정

DB 원본 조회로 구현한 홈·통계·목록·지출 생성 API의 기준선을 잰다. 월 합계 캐시 등 개선 실험은 이 기준선과 비교한다(`docs/00-renewal-development-guide.md` 캐시 절).

| 파일 | 내용 |
| --- | --- |
| `seed.sql` | 측정용 사용자·예산·지출 생성. 등급별 월 지출 건수: normal 30, mid 100, heavy 300 |
| `explain.sql` | Repository `@Query`와 같은 쿼리의 `EXPLAIN (ANALYZE, BUFFERS)` |
| `k6/baseline.js` | 엔드포인트별·혼합 부하 측정(워밍업 10초 + 측정 30초, VU 20) |
| `results/` | 측정 결과 기록 |

## 절차

측정 전용 DB에서만 실행한다. `seed.sql`은 `expenses`, `category_goals`, `monthly_budgets`, `users` 네 테이블을 `TRUNCATE`하고 `users`, `monthly_budgets`, `expenses`만 채운다(`category_goals`는 비워 둔다).

```sh
# 1. 측정용 PostgreSQL. 데이터는 tmpfs에만 두고 지울 때는 항상 -v를 붙인다.
docker run -d --name petgyebu-perf-pg --shm-size=256m \
  --tmpfs /var/lib/postgresql/data:rw,size=512m \
  -e POSTGRES_USER=perf -e POSTGRES_PASSWORD=perf -e POSTGRES_DB=perf \
  -p 127.0.0.1:55432:5432 postgres:16

# 2. 가짜 로그인(ok:<id>) 앱을 측정 DB로 실행한다. Flyway가 스키마를 만든다.
#    PETGYEBU_JWT_SECRET 환경 변수가 필요하다.
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:55432/perf \
SPRING_DATASOURCE_USERNAME=perf SPRING_DATASOURCE_PASSWORD=perf \
SPRING_DOCKER_COMPOSE_ENABLED=false SERVER_PORT=8090 \
./gradlew bootTestRun --no-daemon

# 3. Flyway 적용 후 시드
docker exec -i petgyebu-perf-pg psql -U perf -d perf -v users=1000 -v months=12 -f - < perf/seed.sql

# 4. 실행 계획 (uid 60: normal, 1: mid, 100: heavy / month_offset 0: 이번 달, -6: 여섯 달 전)
docker exec -i petgyebu-perf-pg psql -U perf -d perf -v uid=100 -v month_offset=0 -f - < perf/explain.sql

# 5. 부하. create와 mixed는 지출을 추가하므로 마지막에 돌리거나 다시 시드한 뒤 돌린다.
#    사용자별 월 지출 상한(1,000건)에 닿으면 생성이 409로 바뀌어 거절 경로를 재게 되므로, 길게 돌릴 때는 USERS를 늘린다.
k6 run -e TIER=normal -e ENDPOINT=home --summary-export out.json perf/k6/baseline.js
#    summary의 setup_data에는 액세스 토큰이 있다. 저장소에 넣기 전에 지운다.
jq 'del(.setup_data)' out.json > perf/results/<날짜>/k6/normal_home.json

# 6. 정리
docker rm -f -v petgyebu-perf-pg
```

시드 규모는 디스크·메모리에 맞춰 `users`, `months`로 정한다. 사용자 1,000명 × 12개월은 지출 46.8만 건, `expenses` 83MB다. 1,000명 규모는 tmpfs를 2g, `--shm-size`를 1g로 늘린다. 메모리가 부족하면 200명 × 12개월로 줄이고 k6에 `-e USERS=200 -e VUS=10`을 넘긴다(heavy는 uid 100, 200 두 명).

## DB 왕복 지연 주입

출시 환경(Cloud Run → DB)처럼 요청마다 DB까지 네트워크 왕복이 있는 상황은 `perf/latency.sh`로 흉내 낸다. 측정 DB(`pg_stat_statements` 포함) 앞에 toxiproxy(`ghcr.io/shopify/toxiproxy:2.12.0`)를 두고 앱을 프록시 포트로 연결한다. 이 스크립트가 위 절차 1번 대신 DB를 띄운다.

```sh
perf/latency.sh up            # DB 127.0.0.1:55432, 프록시 127.0.0.1:55433
perf/latency.sh set 1         # toxic 지연(ms). 0이면 제거

# 실제 증가분 확인(로컬 psql이 없어도 된다). 매 측정 세션 시작 때 한 번 한다.
docker run --rm --network petgyebu-perf -e PGPASSWORD=perf postgres:16 \
  psql -h petgyebu-perf-proxy -p 55433 -U perf -d perf -c '\timing on' -c 'select 1' -c 'select 1' -c 'select 1'

# 앱은 프록시로 연결하고 힙을 제한한다.
JAVA_TOOL_OPTIONS=-Xmx512m \
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:55433/perf \
SPRING_DATASOURCE_USERNAME=perf SPRING_DATASOURCE_PASSWORD=perf \
SPRING_DOCKER_COMPOSE_ENABLED=false SERVER_PORT=8090 \
./gradlew bootTestRun --no-daemon

# 시드는 DB 컨테이너에 직접 넣는다.
docker exec -i petgyebu-perf-pg psql -U perf -d perf -v users=200 -v months=12 -f - < perf/seed.sql

perf/latency.sh down          # 컨테이너·네트워크를 볼륨까지 지운다
```

- toxiproxy 지연은 정수 ms이고 Docker Desktop(macOS)에서는 타이머 오차가 더해진다. 2026-11-02 보정값(`select 1` 8회 중앙값, 지연 없음 약 0.2ms): `set 1` → 약 2.0ms, `set 2` → 약 3.9ms, `set 4` → 약 5.3ms, `set 5` → 약 6.0ms. 그래서 왕복 약 2ms는 `set 1`, 약 5ms는 `set 4`로 잡고, 결과에는 설정값이 아니라 확인한 증가분을 적는다.
- 요청당 DB 문장 수: `docker exec petgyebu-perf-pg psql -U perf -d perf -c 'select pg_stat_statements_reset()'` → k6 한 시나리오 → `select calls, query from pg_stat_statements order by calls desc`. `track_utility=on`이라 `BEGIN`·`COMMIT`·`SET`도 센다. 문장 수는 왕복 수의 근사다(드라이버가 여러 문장을 한 번에 보내면 왕복이 더 적을 수 있다).
