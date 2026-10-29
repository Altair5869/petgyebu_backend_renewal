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
docker run -d --name petgyebu-perf-pg --shm-size=1g \
  --tmpfs /var/lib/postgresql/data:rw,size=2g \
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
k6 run -e TIER=normal -e ENDPOINT=home --summary-export out.json perf/k6/baseline.js
#    summary의 setup_data에는 액세스 토큰이 있다. 저장소에 넣기 전에 지운다.
jq 'del(.setup_data)' out.json > perf/results/<날짜>/k6/normal_home.json

# 6. 정리
docker rm -f -v petgyebu-perf-pg
```

시드 규모는 디스크·메모리에 맞춰 `users`, `months`로 정한다. 사용자 1,000명 × 12개월은 지출 46.8만 건, `expenses` 83MB다.
