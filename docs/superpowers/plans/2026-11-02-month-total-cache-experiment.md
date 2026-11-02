# 월 합계 캐시 실험 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** DB 왕복 지연을 주입한 소규모 측정으로 홈·통계가 캐시 없이 충분한지 판정하고, 월 합계 캐시의 효과 상한을 재서 채택 여부를 결론 낸다.

**Architecture:** 측정 DB(PostgreSQL 16, tmpfs, `pg_stat_statements`) 앞에 toxiproxy 컨테이너를 두고 앱(`bootTestRun`, 가짜 로그인)을 프록시로 연결해 왕복 지연 0/2/5ms에서 k6로 잰다. 캐시 상한은 "적중 비용 0인 캐시"와 같은 **월 합계 조회 생략 스텁**을 머지하지 않는 별도 브랜치에서 켜서 잰다. 상한이 30% 미만이면 실제 캐시는 만들지 않는다.

**Tech Stack:** Java 25, Spring Boot 4.1.1, PostgreSQL 16(docker), toxiproxy(docker, 측정 전용), k6, jq

**Spec:** `docs/superpowers/specs/2026-11-02-month-total-cache-experiment-design.md`

## Global Constraints

- 규모: 사용자 200명 × 12개월(`-v users=200 -v months=12`). heavy = uid 100, 200.
- 메모리 상한: 측정 DB tmpfs `size=512m`, `--shm-size=256m`, 앱 JVM `-Xmx512m`. 측정 후 `docker rm -f -v`로 컨테이너·볼륨을 지운다.
- k6: `-e USERS=200 -e VUS=10`, 워밍업 10초 + 측정 30초(스크립트 기본).
- 판정 조건: 지연 2ms(조건 B). 5ms는 기록만(50ms 초과 시 위험으로 기록).
- 판정에 쓰는 시나리오는 3회 반복, 중앙값 p95.
- 기준: p95 ≤ 50ms면 충분. 캐시 채택 제안은 (50ms 초과) ∧ (p95 30% 이상 감소) ∧ (정합성 통과).
- `develop`에 새 런타임 의존성·실험 캐시·스텁 코드를 넣지 않는다. 측정 도구는 docker 컨테이너로만 쓴다.
- k6 JSON은 커밋 전에 `jq 'del(.setup_data)'`로 토큰을 지운다.
- 개발 DB(`petgyebu_backend_renewal-postgres-1`)는 건드리지 않는다. 측정 DB 포트 55432, 프록시 포트 55433, 앱 포트 8090.
- 기존 결정을 바꾸는 개선(월 합계 SQL `SUM` 복귀, `hasActiveSession` 흐름 변경)은 결과 문서에 제안만 한다.
- 작업 브랜치: `chore/cache-experiment`(측정 도구·결과·문서, develop으로 PR). 스텁 브랜치: `experiment/month-total-upper-bound`(push하지 않음, 머지하지 않음).

## Review Focus

1. 측정 후 컨테이너·tmpfs가 남아 노트북 메모리를 계속 쓰는 경우 → 각 측정 Task 끝에 `docker ps -a | grep petgyebu-perf` 결과가 비어 있어야 한다.
2. toxiproxy 지연이 실제로 걸리지 않았는데 결과를 조건 B로 기록하는 경우 → Task 1의 지연 확인 명령(psql `\timing` 왕복 시간 증가)을 매 세션 시작 때 다시 실행한다.
3. 스텁 브랜치 앱으로 기준 측정을 하거나 그 반대 → 결과 문서에 시나리오마다 앱 커밋 SHA를 적고, Task 4에서 앱 시작 로그의 스텁 속성 값을 확인한다.
4. 데이터를 바꾸는 시나리오(create, mixed)를 판정 시나리오 사이에 돌려 행 수가 달라지는 경우 → 이번 계획은 create·mixed를 돌리지 않는다.
5. k6 summary의 토큰이 커밋되는 경우 → 커밋 전에 `grep -r accessToken perf/results/2026-11-02-cache-experiment/` 결과가 비어 있어야 한다.

---

### Task 1: 지연 주입 측정 환경과 절차 문서

**Files:**
- Create: `perf/latency.sh`
- Modify: `perf/README.md` (컨테이너 예시의 메모리 값, 지연 주입·`pg_stat_statements` 절차 추가)

**Interfaces:**
- Produces: `perf/latency.sh up|set <ms>|down` — `up`은 docker 네트워크 `petgyebu-perf`, `petgyebu-perf-pg`(5432, 호스트 55432), `petgyebu-perf-proxy`(toxiproxy, 호스트 55433→pg:5432, API 8474)를 만든다. `set <ms>`는 upstream·downstream 각각 `latency=<ms>/2`ms toxic을 설정한다(0이면 toxic 제거). `down`은 두 컨테이너와 네트워크를 `-v`로 지운다.
- 측정 DB는 `-c shared_preload_libraries=pg_stat_statements -c pg_stat_statements.track_utility=on`으로 띄운다.
- toxiproxy 이미지는 `ghcr.io/shopify/toxiproxy`의 고정 버전 태그를 쓴다(작성 시점 최신 안정 태그를 확인해 README에 적는다).

- [ ] **Step 1: `perf/latency.sh` 작성** — `set -euo pipefail`, 위 인터페이스대로. 프록시 생성과 toxic 설정은 toxiproxy HTTP API(`curl -s -X POST localhost:8474/proxies`, `/proxies/pg/toxics`)로 한다.
- [ ] **Step 2: 환경을 올리고 지연 확인**

Run:
```sh
perf/latency.sh up
for ms in 0 2 5; do perf/latency.sh set $ms; \
  PGPASSWORD=perf psql -h 127.0.0.1 -p 55433 -U perf -d perf -c '\timing on' -c 'select 1' -c 'select 1' -c 'select 1' | grep Time; done
```
Expected: 0ms일 때보다 2ms에서 `select 1`이 약 2ms, 5ms에서 약 5ms 늘어난다.

- [ ] **Step 3: README 갱신** — 1번 컨테이너 예시를 512m/256m로, 지연 주입(`latency.sh`), 앱을 55433으로 연결하는 실행 예(`JAVA_TOOL_OPTIONS=-Xmx512m`, `SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:55433/perf`), 요청당 문장 수 측정(`pg_stat_statements_reset()` → k6 → `select query, calls from pg_stat_statements`), k6 `-e USERS=200 -e VUS=10`을 적는다.
- [ ] **Step 4: 정리 확인** — `perf/latency.sh down && docker ps -a | grep petgyebu-perf` → 출력 없음.
- [ ] **Step 5: Commit**

```bash
git add perf/latency.sh perf/README.md
git commit -m "chore: add a DB latency proxy to the perf setup"
```

### Task 2: 요청당 DB 문장 수 측정

**Files:**
- Create: `perf/results/2026-11-02-cache-experiment/statements/{home,stats_current,stats_past}_{normal,heavy}.txt`

**Interfaces:**
- Consumes: Task 1의 `perf/latency.sh`, README 절차.
- Produces: 엔드포인트별 "요청 1건당 문장 수"(`calls ÷ k6 요청 수`, `BEGIN`/`COMMIT`/`SET` 포함 여부를 분리)를 Task 5 결과 문서가 쓴다.

- [ ] **Step 1: 환경 기동과 시드** — `perf/latency.sh up`, `latency.sh set 0`, 앱을 55433으로 `bootTestRun`(포트 8090) 실행, Flyway 후 `perf/seed.sql`을 `-v users=200 -v months=12`로 실행.
- [ ] **Step 2: 시나리오마다 측정** — 각 (ENDPOINT, TIER)에 대해 `select pg_stat_statements_reset();` → `k6 run -e ENDPOINT=<e> -e TIER=<t> -e USERS=200 -e VUS=10 -e WARMUP=0s -e DURATION=10s` → `select calls, query from pg_stat_statements order by calls desc`를 파일로 저장. k6 요청 수(`http_reqs`)도 같은 파일 머리에 적는다.
- [ ] **Step 3: 확인** — home 파일에서 `hasActiveSession`, `findById`, budget `find`, `findAmounts` 쿼리의 calls가 각각 요청 수와 같다(±로그인 setup 호출). 다르면 원인을 파일 끝에 적는다.
- [ ] **Step 4: Commit**

```bash
git add perf/results/2026-11-02-cache-experiment/statements
git commit -m "chore: record DB statements per request for home and stats"
```

### Task 3: 지연 조건별 기준 부하 측정 (캐시 없음)

**Files:**
- Create: `perf/results/2026-11-02-cache-experiment/k6/base_<ms>ms_<tier>_<endpoint>_r<n>.json`

**Interfaces:**
- Consumes: Task 2와 같은 시드 DB와 앱 실행(재시드 금지).
- Produces: 조건 0/2/5ms × {normal, heavy} × {home, stats_current, stats_past}의 p95. 2ms는 3회(r1~r3), 0ms·5ms는 1회.

- [ ] **Step 1: 측정** — 지연마다 `latency.sh set <ms>` 후 Task 1 Step 2의 지연 확인을 한 번 하고, 시나리오를 돌려 `--summary-export` → `jq 'del(.setup_data)'`로 저장.
- [ ] **Step 2: 표 산출** — 각 파일의 `metrics."http_req_duration{phase:measure}"."p(95)"`와 실패율을 뽑아 2ms는 중앙값을 계산한다. 실패율이 0이 아니면 그 시나리오를 다시 돌리고 이유를 기록한다.
- [ ] **Step 3: 확인** — `grep -rl accessToken perf/results/2026-11-02-cache-experiment/` 출력 없음.
- [ ] **Step 4: Commit**

```bash
git add perf/results/2026-11-02-cache-experiment/k6
git commit -m "chore: measure home and stats with injected DB latency"
```

### Task 4: 월 합계 캐시 효과 상한 (스텁, 머지하지 않음)

**Files:**
- Modify (스텁 브랜치에서만): `src/main/java/org/altair/petgyebu_backend_renewal/home/HomeService.java`
- Create: `perf/results/2026-11-02-cache-experiment/k6/stub_2ms_<tier>_home_r<n>.json`, `perf/results/2026-11-02-cache-experiment/upper-bound-stub.diff`

**Interfaces:**
- Consumes: Task 3의 같은 시드 DB, 2ms 조건.
- Produces: 홈 normal·heavy의 스텁 p95 중앙값과 `상한 = 1 − stub_p95 / base_p95`.

- [ ] **Step 1: 스텁 브랜치 생성** — `git switch -c experiment/month-total-upper-bound`(`chore/cache-experiment`에서). `HomeService.home`에 속성 `petgyebu.perf.skip-month-total`(기본 false)이 true면 `findAmounts`를 호출하지 않고 `spent = 0`으로 둔다. 캐시 적중 비용 0을 흉내 내는 것이며 테스트·문서는 만들지 않는다. `git diff develop -- src > upper-bound-stub.diff`로 저장해 둔다(결과 문서 근거용). 이 브랜치는 push하지 않는다.
- [ ] **Step 2: 측정** — 앱을 `--args='--petgyebu.perf.skip-month-total=true'`(또는 같은 환경 변수)로 실행하고 시작 로그·`/home` 응답의 `spentAmount`가 0인지로 스텁이 켜졌는지 확인한다. 2ms에서 home normal·heavy를 3회씩 측정.
- [ ] **Step 3: 상한 계산과 분기**
  - 상한 < 30%(normal, heavy 모두) → 비채택. 다음 Task로.
  - 어느 쪽이든 상한 ≥ 30%이고 그 시나리오의 base p95가 50ms 초과 → **멈추고 사용자에게 보고**한다. 실제 캐시 구현(스펙 6.3)과 정합성 검증(6.4)은 이 결과를 근거로 새 계획을 써서 진행한다.
  - 상한 ≥ 30%지만 base p95 ≤ 50ms → 기준 1로 "충분"이므로 비채택. 상한 값은 기록한다.
- [ ] **Step 4: 스텁 브랜치 정리** — 결과 파일과 diff를 `chore/cache-experiment`로 가져온 뒤(`git switch chore/cache-experiment`; 결과 파일은 untracked 상태로 남겨 둠) 커밋. 앱 실행 중지, `perf/latency.sh down`, `docker ps -a | grep petgyebu-perf` 출력 없음 확인.

```bash
git add perf/results/2026-11-02-cache-experiment/k6/stub_* perf/results/2026-11-02-cache-experiment/upper-bound-stub.diff
git commit -m "chore: measure the upper bound of a month total cache with a stub"
```

### Task 5: 결과 문서와 결정 반영

**Files:**
- Create: `perf/results/2026-11-02-cache-experiment.md`
- Modify: `docs/00-renewal-development-guide.md` (9절 후속 결정 표 "캐시의 출시 적용과 구현 제품" 행), `docs/05-infra-stack.md` (4절), `docs/10-task-backlog.md` (출시 준비 표 "월별 지출 합계 캐시 실험" 행)

**Interfaces:**
- Consumes: Task 2~4의 모든 결과.

- [ ] **Step 1: 결과 문서 작성** — 기존 `2026-10-27-monthly-spending.md` 형식을 따른다. 포함: 환경(장비, 커밋 SHA, 메모리 상한, toxiproxy 버전), 요청당 문장 수 표, 0/2/5ms × 시나리오 p95 표(2ms는 r1~r3와 중앙값), 지연으로 늘어난 시간 ≈ 문장 수 × 지연 해석, 스텁 상한 표와 계산, 판정(기준 1·2별), 5ms 위험 여부, 캐시 외 개선 후보를 "바로 PR 가능" / "제안(승인 필요: SQL SUM 복귀, hasActiveSession 흐름)"로 나눈 목록과 각 근거 수치. 측정 노이즈 주의(한 세션 쌍 비교)를 적는다.
- [ ] **Step 2: 문서 갱신** — 결론(비채택 또는 보류·추가 실험)을 00 표, 05 4절, 10 표에 한두 문장으로 반영하고 결과 문서로 링크한다. 비채택이면 "Redis 등 캐시 서비스 불필요" 문장은 유지한다.
- [ ] **Step 3: 테스트** — Run: `./gradlew test --no-daemon` → Expected: BUILD SUCCESSFUL(코드 변경 없음 확인용).
- [ ] **Step 4: Commit**

```bash
git add perf/results/2026-11-02-cache-experiment.md docs/00-renewal-development-guide.md docs/05-infra-stack.md docs/10-task-backlog.md
git commit -m "docs: record the month total cache experiment result"
```

### Task 6: 독립 검토와 PR

- [ ] **Step 1: qa-engineer 검토** — `.codex/agents/qa-engineer.toml` 역할로 결과 문서의 수치가 원본 JSON·문장 수 파일과 일치하는지, 판정이 스펙 5절 규칙(2ms, 3회 중앙값, 50ms/30%)대로인지, 토큰·비밀값이 없는지, 스텁 코드가 브랜치에 없는지(`git diff develop -- src` 출력 없음)를 확인한다.
- [ ] **Step 2: 지적 반영 후 PR** — `chore/cache-experiment` → `develop`. PR 본문에 결론, 핵심 표, 제안 목록(승인 필요 항목 구분), 확인하지 않은 것(실제 Cloud Run·Supabase)을 적는다.
- [ ] **Step 3: 캐시 외 개선** — 결과 문서의 "바로 PR 가능" 항목은 이 PR 머지 후 항목마다 별도 브랜치·전후 측정으로 진행한다. "제안" 항목은 사용자 승인 전에는 손대지 않는다.
