#!/usr/bin/env bash
# 측정 DB 앞에 toxiproxy를 두어 DB 왕복 지연을 주입한다. 측정 전용이며 개발 DB를 건드리지 않는다.
# 사용: perf/latency.sh up | set <toxic ms> | down
#   up    측정 DB(호스트 55432)와 프록시(호스트 55433 → DB, API 127.0.0.1:8474)를 띄운다
#   set   보내기 방향에 <ms> 지연 toxic을 건다. 0이면 지연을 없앤다. 실제 증가분은 README 보정값 참고
#   down  두 컨테이너와 네트워크를 볼륨까지 지운다
set -euo pipefail

NET=petgyebu-perf
PG=petgyebu-perf-pg
PROXY=petgyebu-perf-proxy
API=http://127.0.0.1:8474
TOXIPROXY_IMAGE=ghcr.io/shopify/toxiproxy:2.12.0

clear_toxics() {
  for t in up down; do
    curl -s -o /dev/null -X DELETE "$API/proxies/pg/toxics/latency_$t" || true
  done
}

case "${1:-}" in
  up)
    docker network create "$NET" >/dev/null
    docker run -d --name "$PG" --network "$NET" --shm-size=256m \
      --tmpfs /var/lib/postgresql/data:rw,size=512m \
      -e POSTGRES_USER=perf -e POSTGRES_PASSWORD=perf -e POSTGRES_DB=perf \
      -p 127.0.0.1:55432:5432 postgres:16 \
      -c shared_preload_libraries=pg_stat_statements -c pg_stat_statements.track_utility=on >/dev/null
    docker run -d --name "$PROXY" --network "$NET" \
      -p 127.0.0.1:55433:55433 -p 127.0.0.1:8474:8474 "$TOXIPROXY_IMAGE" >/dev/null
    until docker exec "$PG" pg_isready -U perf -d perf -q; do sleep 1; done
    until curl -sf "$API/version" >/dev/null; do sleep 1; done
    curl -sf -X POST "$API/proxies" \
      -d "{\"name\":\"pg\",\"listen\":\"0.0.0.0:55433\",\"upstream\":\"$PG:5432\"}" >/dev/null
    docker exec "$PG" psql -U perf -d perf -qc 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements'
    echo "up: db 127.0.0.1:55432, proxy 127.0.0.1:55433"
    ;;
  set)
    ms="${2:?set <toxic ms>}"
    clear_toxics
    if [ "$ms" != 0 ]; then
      # 왕복 지연 전체를 보내기 방향 한쪽에 준다. 양방향에 나누면 toxic마다 타이머 오차(약 1ms)가
      # 더해져 설정값보다 크게 늘어난다. 실제 증가분은 README 절차의 psql \timing으로 확인해 기록한다.
      curl -sf -X POST "$API/proxies/pg/toxics" \
        -d "{\"name\":\"latency_up\",\"type\":\"latency\",\"stream\":\"upstream\",\"attributes\":{\"latency\":$ms}}" >/dev/null
    fi
    echo "latency toxic: ${ms}ms"
    ;;
  down)
    docker rm -f -v "$PROXY" "$PG" >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
    echo "down"
    ;;
  *)
    echo "usage: $0 up | set <ms> | down" >&2
    exit 2
    ;;
esac
