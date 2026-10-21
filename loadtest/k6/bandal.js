// Phase 4 부하 시나리오. 운영 서버에서 테스트 서버(사설 IP)로 건다. 데이터는 loadtest/seed.sql
//   SCENARIO=A  한 방에 몰림: JOINERS명이 같은 순간에 한 방(정원 10)에 참여한다
//   SCENARIO=B  목록 새로고침: 큰 학교, 작은 학교 사용자가 단계로 늘며 방 목록을 부른다
//   SCENARIO=C  A와 B를 같이: 목록과 다른 방 보기가 도는 중간에 A가 터진다. 다른 요청까지 느려지는지 본다
// 로그인은 setup()에서 미리 한다. BCrypt는 일부러 느린 연산이라 측정 중에 섞이면 숫자가 로그인 비용이 된다
// 판정선(thresholds)은 테스트 전에 정한 값이다: 목록 p95 300ms, 참여 p95 1초, 에러 1% 미만
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE || 'http://10.0.0.28:8080';
const SCENARIO = (__ENV.SCENARIO || 'B').toUpperCase();
const PASSWORD = __ENV.PASSWORD || 'loadtest-pass-1';
const BIG_UID = Number(__ENV.BIG_UID || 2);       // 연세대 신촌
const BIG_DOMAIN = __ENV.BIG_DOMAIN || 'yonsei.ac.kr';
const SMALL_UID = Number(__ENV.SMALL_UID || 13);  // 광운대
const SMALL_DOMAIN = __ENV.SMALL_DOMAIN || 'kw.ac.kr';
const HOT_ROOM = Number(__ENV.HOT_ROOM || 0);
const JOINERS = Number(__ENV.JOINERS || 40);
// B, C에서 단계마다 머무는 시간. 짧게 돌려 보려면 줄인다
const STEP = __ENV.STEP || '1m';

const joinOk = new Counter('join_ok');
const joinFull = new Counter('join_full');

// 참여는 409(정원 초과, 이미 참여)도 정상 응답이다. 실패로 세지 않는다
const joinStatuses = http.expectedStatuses(201, 409);

function ramp(peak) {
  return [
    { duration: '20s', target: Math.round(peak / 4) },
    { duration: STEP, target: Math.round(peak / 4) },
    { duration: '20s', target: Math.round(peak / 2) },
    { duration: STEP, target: Math.round(peak / 2) },
    { duration: '20s', target: peak },
    { duration: STEP, target: peak },
    { duration: '10s', target: 0 },
  ];
}

const scenarios = {
  A: {
    hot_join: { executor: 'per-vu-iterations', vus: JOINERS, iterations: 1, exec: 'hotJoin', maxDuration: '1m' },
  },
  B: {
    list_big: { executor: 'ramping-vus', stages: ramp(Number(__ENV.PEAK_BIG || 200)), exec: 'browseBig' },
    list_small: { executor: 'ramping-vus', stages: ramp(Number(__ENV.PEAK_SMALL || 40)), exec: 'browseSmall' },
  },
  C: {
    // 목록과 다른 방 보기가 3분 동안 고르게 돈다
    list_big: { executor: 'constant-vus', vus: Number(__ENV.BG_VUS || 100), duration: '3m', exec: 'browseBig' },
    // 1분 뒤에 한 방에 몰린다. 그 앞과 뒤의 목록 p95를 비교한다
    hot_join: { executor: 'per-vu-iterations', vus: JOINERS, iterations: 1, exec: 'hotJoin', startTime: '1m', maxDuration: '1m' },
  },
};

export const options = {
  scenarios: scenarios[SCENARIO],
  setupTimeout: '3m',
  thresholds: {
    'http_req_duration{name:list}': ['p(95)<300'],
    'http_req_duration{name:list,school:big}': ['p(95)<300'],
    'http_req_duration{name:list,school:small}': ['p(95)<300'],
    'http_req_duration{name:join}': ['p(95)<1000'],
    // 방 보기는 판정선이 없다. 요약에 따로 나오게 하려면 이름별 지표를 여기 적어야 해서 넉넉한 값을 둔다
    'http_req_duration{name:detail}': ['p(95)<60000'],
    'http_req_failed{phase:measure}': ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function login(uid, domain, i) {
  const res = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email: `lt-${uid}-${i}@${domain}`, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login', phase: 'setup' } });
  if (res.status !== 200) throw new Error(`로그인 실패 ${uid}-${i}: ${res.status} ${res.body}`);
  return res.json('accessToken');
}

export function setup() {
  if ((SCENARIO === 'A' || SCENARIO === 'C') && !HOT_ROOM) throw new Error('HOT_ROOM을 주세요(seed.sql 마지막 출력)');
  const big = [], small = [], joiners = [];
  const nBig = SCENARIO === 'A' ? 0 : Number(__ENV.PEAK_BIG || __ENV.BG_VUS || 200);
  const nSmall = SCENARIO === 'B' ? Number(__ENV.PEAK_SMALL || 40) : 0;
  for (let i = 1; i <= nBig; i++) big.push(login(BIG_UID, BIG_DOMAIN, i));
  for (let i = 1; i <= nSmall; i++) small.push(login(SMALL_UID, SMALL_DOMAIN, i));
  // 참여자는 목록 사용자와 겹치지 않는 번호에서 고른다. 1번은 핫한 방의 방장이다
  if (SCENARIO !== 'B') for (let i = 0; i < JOINERS; i++) joiners.push(login(BIG_UID, BIG_DOMAIN, 1001 + i));
  const rooms = big.length ? http.get(`${BASE}/api/group-orders`, auth(big[0], 'setup', 'list')).json('rooms').map((r) => r.id) : [];
  return { big, small, joiners, rooms };
}

// 태그로 요청을 나눠 센다. name: 어떤 요청, phase: 측정 중인지, school: 큰 학교와 작은 학교
function auth(token, phase, name, school) {
  const tags = { phase, name };
  if (school) tags.school = school;
  return { headers: { Authorization: `Bearer ${token}` }, tags };
}

// 목록을 열고, 셋 중 하나는 방 하나를 눌러 본다. 사람처럼 1~2초 쉰다
function browse(tokens, rooms, school) {
  const token = tokens[(exec.vu.idInTest - 1) % tokens.length];
  const res = http.get(`${BASE}/api/group-orders`, auth(token, 'measure', 'list', school));
  check(res, { '목록 200': (r) => r.status === 200 });
  if (rooms.length && exec.vu.iterationInScenario % 3 === 0) {
    const id = rooms[(exec.vu.idInTest + exec.vu.iterationInScenario) % rooms.length];
    const d = http.get(`${BASE}/api/group-orders/${id}`, auth(token, 'measure', 'detail'));
    check(d, { '방 보기 200': (r) => r.status === 200 });
  }
  sleep(1 + Math.random());
}

export function browseBig(data) { browse(data.big, data.rooms, 'big'); }
export function browseSmall(data) { browse(data.small, [], 'small'); }

export function hotJoin(data) {
  // VU 번호는 시나리오를 넘어 전체에서 매겨져서, 이 시나리오 안의 반복 번호(0부터)로 참여자를 고른다
  const token = data.joiners[exec.scenario.iterationInTest];
  const res = http.post(`${BASE}/api/group-orders/${HOT_ROOM}/participations`, null,
    Object.assign(auth(token, 'measure', 'join'), { responseCallback: joinStatuses }));
  if (res.status === 201) joinOk.add(1);
  if (res.status === 409) joinFull.add(1);
  check(res, { '참여 201 또는 409': (r) => r.status === 201 || r.status === 409 });
}

// 끝나고 정원이 정확히 지켜졌는지 본다. 정원 10에 방장 1명이라 9명만 들어가야 한다
export function teardown(data) {
  if (!HOT_ROOM) return;
  const token = data.joiners[0];
  // 측정 숫자(방 보기)에 섞이지 않게 이름을 따로 준다
  const room = http.get(`${BASE}/api/group-orders/${HOT_ROOM}`, auth(token, 'teardown', 'verify'));
  console.log(`핫한 방 참여 인원: ${room.json('participantCount')} / 정원 ${room.json('capacity')}`);
}

export function handleSummary(data) {
  const name = `/results/${SCENARIO}-${new Date().toISOString().replace(/[:.]/g, '-')}.json`;
  return { [name]: JSON.stringify(data, null, 1), stdout: textSummary(data) };
}

// k6 기본 요약 대신 판정에 쓰는 숫자만 짧게 찍는다
function textSummary(data) {
  const m = data.metrics;
  const out = [`== 시나리오 ${SCENARIO}`];
  const tags = [['목록(큰 학교)', 'name:list,school:big'], ['목록(작은 학교)', 'name:list,school:small'],
    ['방 보기', 'name:detail'], ['참여', 'name:join']];
  for (const [label, key] of tags) {
    const d = m[`http_req_duration{${key}}`];
    // 그 요청이 한 번도 없었으면 값이 전부 0이다
    if (!d || !d.values.max) continue;
    const v = d.values;
    out.push(`${label}  p95 ${v['p(95)'].toFixed(1)}ms  p99 ${v['p(99)'].toFixed(1)}ms  max ${v.max.toFixed(1)}ms`);
  }
  const f = m['http_req_failed{phase:measure}'];
  if (f) out.push(`에러율  ${(f.values.rate * 100).toFixed(2)}%`);
  if (m.join_ok) out.push(`참여 성공 ${m.join_ok.values.count}, 정원 초과 409 ${(m.join_full || { values: { count: 0 } }).values.count}`);
  out.push('-- 판정선');
  for (const [k, v] of Object.entries(m)) {
    if (v.thresholds) out.push(`${Object.values(v.thresholds).every((t) => t.ok) ? '통과' : '넘음'}  ${k}`);
  }
  return out.join('\n') + '\n';
}