// 기준선 부하 측정. bootTestRun(가짜 로그인)과 perf/seed.sql로 채운 측정 전용 DB에서만 실행한다.
// 사용: k6 run -e ENDPOINT=home -e TIER=normal perf/k6/baseline.js
//   ENDPOINT: home | stats_current | stats_past | list_first | list_next | create | mixed
//   TIER: normal | heavy (perf/seed.sql의 사용자 등급)
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090/api/v1';
const SEEDED_USERS = Number(__ENV.USERS || 1000);
const ENDPOINT = __ENV.ENDPOINT || 'mixed';
const TIER = __ENV.TIER || 'normal';
const LOGIN_USERS = Number(__ENV.LOGIN_USERS || 50);

export const options = {
  scenarios: {
    load: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 20),
      duration: __ENV.DURATION || '30s',
      startTime: __ENV.WARMUP || '10s',
      tags: { phase: 'measure' },
    },
    warmup: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 20),
      duration: __ENV.WARMUP || '10s',
      tags: { phase: 'warmup' },
    },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  // 측정 구간만 집계한다. 조건은 항상 통과하며 하위 지표를 요약에 표시하려고 둔다.
  thresholds: {
    'http_req_duration{phase:measure}': ['p(99)>=0'],
    'http_req_failed{phase:measure}': ['rate>=0'],
    ...Object.fromEntries(['home', 'stats_current', 'stats_past', 'list_first', 'list_next', 'create']
      .map((n) => [`http_req_duration{phase:measure,name:${n}}`, ['p(99)>=0']])),
  },
};

// perf/seed.sql: i % 100 = 0 → heavy, i % 100 < 10 → mid, 그 외 → normal
function userIdsOf(tier) {
  const ids = [];
  for (let i = 1; i <= SEEDED_USERS && ids.length < LOGIN_USERS; i++) {
    const t = i % 100 === 0 ? 'heavy' : i % 100 < 10 ? 'mid' : 'normal';
    if (t === tier) ids.push(i);
  }
  return ids;
}

function seoulMonth(offset) {
  const now = new Date(Date.now() + 9 * 3600 * 1000);
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + offset, 1));
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
}

function seoulToday() {
  return new Date(Date.now() + 9 * 3600 * 1000).toISOString().slice(0, 10);
}

export function setup() {
  // 한 사용자당 활성 로그인은 하나이므로 사용자마다 한 번만 로그인하고 VU들이 토큰을 나눠 쓴다.
  const tokens = userIdsOf(TIER).map((id) => {
    const res = http.post(`${BASE_URL}/auth/login/kakao`,
      JSON.stringify({ credential: `ok:perf-${String(id).padStart(5, '0')}` }),
      { headers: { 'Content-Type': 'application/json' } });
    if (res.status !== 200) throw new Error(`login failed for ${id}: ${res.status}`);
    return res.json('accessToken');
  });
  return { tokens, current: seoulMonth(0), past: seoulMonth(-6), today: seoulToday() };
}

function get(path, token, name) {
  return http.get(`${BASE_URL}${path}`, { headers: { Authorization: `Bearer ${token}` }, tags: { name } });
}

const actions = {
  home: (d, t) => get('/home', t, 'home'),
  stats_current: (d, t) => get(`/months/${d.current}/stats`, t, 'stats_current'),
  stats_past: (d, t) => get(`/months/${d.past}/stats`, t, 'stats_past'),
  list_first: (d, t) => get(`/months/${d.current}/expenses`, t, 'list_first'),
  list_next: (d, t) => {
    const first = http.get(`${BASE_URL}/months/${d.past}/expenses`,
      { headers: { Authorization: `Bearer ${t}` }, tags: { name: 'list_next_setup', phase: 'setup_call' } });
    const cursor = first.json('nextCursor');
    return cursor ? get(`/months/${d.past}/expenses?cursor=${encodeURIComponent(cursor)}`, t, 'list_next') : first;
  },
  create: (d, t) => http.post(`${BASE_URL}/expenses`,
    JSON.stringify({ amount: 4500, expenseDate: d.today, category: 'CAFE', memo: 'perf' }),
    { headers: { Authorization: `Bearer ${t}`, 'Content-Type': 'application/json' }, tags: { name: 'create' } }),
};

// 앱 사용 비율 가정: 홈 40%, 목록 25%, 통계 20%, 지출 기록 15%
const mix = [
  ['home', 40], ['list_first', 15], ['list_next', 10], ['stats_current', 15], ['stats_past', 5], ['create', 15],
];

function pickMixed() {
  let r = Math.random() * 100;
  for (const [name, weight] of mix) {
    if ((r -= weight) < 0) return name;
  }
  return 'home';
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const name = ENDPOINT === 'mixed' ? pickMixed() : ENDPOINT;
  const res = actions[name](data, token);
  check(res, { 'status 2xx': (r) => r.status >= 200 && r.status < 300 });
}
