import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

const data = JSON.parse(open(__ENV.FIXTURE));
const users = new SharedArray('users', () => JSON.parse(open(__ENV.USERS)));
const mode = __ENV.MODE || 'smoke';
const base = __ENV.BASE_URL || 'http://localhost:8080';
const rate = Number(__ENV.RATE || 10);
const duration = __ENV.DURATION || '30s';
const vus = Number(__ENV.VUS || 50);
const arrival = { executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration,
  preAllocatedVUs: Number(__ENV.PRE_VUS || 100), maxVUs: Number(__ENV.MAX_VUS || 500), gracefulStop: '15s' };
let scenario = arrival;
if (mode === 'smoke') scenario = { executor: 'shared-iterations', vus: 1, iterations: 10, maxDuration: '1m' };
if (['hot', 'idempotency'].includes(mode)) scenario = { executor: 'per-vu-iterations', vus, iterations: 10, maxDuration: '2m' };
if (mode === 'overspend') scenario = { executor: 'per-vu-iterations', vus: 20, iterations: 1, maxDuration: '2m' };
if (mode === 'conflict') scenario = { executor: 'per-vu-iterations', vus: 20, iterations: 1, maxDuration: '2m' };
if (mode === 'security') scenario = { executor: 'shared-iterations', vus: 1, iterations: 6, maxDuration: '1m' };
if (mode === 'spike') scenario = { executor: 'ramping-arrival-rate', startRate: 10, timeUnit: '1s',
  preAllocatedVUs: Number(__ENV.PRE_VUS || 100), maxVUs: Number(__ENV.MAX_VUS || 500),
  stages: [{ target: 10, duration: '30s' }, { target: rate, duration: '10s' },
    { target: rate, duration: '30s' }, { target: 10, duration: '10s' }, { target: 10, duration: '60s' }], gracefulStop: '15s' };

const latency = new Trend('api_latency_ms', true);
const unexpected = new Rate('unexpected_response_rate');
const requests = new Counter('measured_requests');
const paymentResponses = new Counter('payment_success_responses');
const thresholds = { checks: ['rate==1'], unexpected_response_rate: ['rate==0'],
  api_latency_ms: [`p(95)<${__ENV.P95_MS || 500}`, `p(99)<${__ENV.P99_MS || 1500}`] };
if (['read','mixed','login','spike','soak'].includes(mode)) thresholds.dropped_iterations = ['count==0'];
for (const name of ['boards','wallet','history','details','payment','login','security']) {
  thresholds[`api_latency_ms{endpoint:${name}}`] = [];
}
export const options = { scenarios: { workload: scenario }, thresholds,
  summaryTrendStats: ['avg','min','med','max','p(95)','p(99)'],
  systemTags: ['status','method','name','scenario','expected_response'],
  summaryTimeUnit: 'ms' };

function key(index) {
  return `${data.prefix}-0000-4000-8000-${Number(index + 1).toString(16).padStart(12,'0')}`;
}
function send(user, path, name, body = null, id = null, expected = [200], token = user.token) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (id) headers['Idempotency-Key'] = id;
  const r = http.request(body === null ? 'GET' : 'POST', base + path,
    body === null ? null : JSON.stringify(body), { headers, timeout: '10s', redirects: 0,
      tags: { name, endpoint: name, phase: 'load' }, responseCallback: http.expectedStatuses(...expected) });
  latency.add(r.timings.duration, { endpoint: name });
  requests.add(1);
  unexpected.add(!expected.includes(r.status));
  check(r, { 'expected HTTP status': x => expected.includes(x.status) });
  if (name === 'payment' && r.status === 200) {
    paymentResponses.add(1);
    check(r, { 'payment has transaction ID': x => Boolean(x.json('transactionId')) });
  }
  return r;
}
function pay(user, amount, id, expected=[200], recipient=user.recipient_wallet_id) {
  return send(user, `/api/boards/${user.board_id}/payments`, 'payment',
    { recipientWalletId: recipient, amount, reason: 'OTHER' }, id, expected);
}
export function setup() {
  if (mode === 'conflict') {
    const u = data.hot[0];
    const r = http.post(`${base}/api/boards/${u.board_id}/payments`, JSON.stringify({
      recipientWalletId: u.recipient_wallet_id, amount: '0.01', reason: 'OTHER'
    }), { headers: { Authorization: `Bearer ${u.token}`, 'Content-Type':'application/json',
      'Idempotency-Key': key(0) }, tags: { name:'conflict_setup', phase:'setup' }, timeout:'10s' });
    if (r.status !== 200) throw new Error(`Conflict setup failed: ${r.status}`);
  }
}
export default function () {
  try { workload(); }
  catch (error) {
    unexpected.add(true);
    check(false, { 'iteration completed without script error': value => value });
    console.error(`Workload script error: ${error.message}`);
  }
}
function workload() {
  const i = exec.scenario.iterationInTest;
  const u = users[i % users.length];
  if (mode === 'login') {
    const r = send(u, '/api/auth/login', 'login', { username:u.username, password:data.password }, null, [200], null);
    check(r, { 'login returns token': x => x.status === 200 && Boolean(x.json('accessToken')) });
    return;
  }
  if (mode === 'hot') return pay(data.hot[(__VU - 1) % 2], '0.01', key(i));
  if (mode === 'idempotency') return pay(data.hot[0], '0.01', key(0));
  if (mode === 'conflict') return pay(data.hot[0], '0.02', key(0), [409]);
  if (mode === 'overspend') return pay(data.hot[0], data.overspend_amount, key(i), [200,400]);
  if (mode === 'security') {
    const h = data.hot[0], path = `/api/boards/${h.board_id}/wallets/me`;
    if (i === 0) return send(h, path, 'security', null, null, [401], null);
    if (i === 1) return send(h, path, 'security', null, null, [401], 'invalid.jwt.signature');
    if (i === 2) return send(h, path, 'security', null, null, [401], data.expired_token);
    if (i === 3) return send(h, `/api/boards/${h.board_id}/payments`, 'security',
      { recipientWalletId:h.recipient_wallet_id, amount:'0.01', reason:'OTHER' }, key(i), [403], data.outsider.token);
    if (i === 4) return pay(h, '0.01', key(i), [404], data.outsider.wallet_id);
    return pay(h, '-1.00', key(i), [400]);
  }
  // Multiplication distributes each endpoint's traffic across the whole user pool.
  const choice = (i * 7 + Math.floor(i / users.length)) % 10;
  if (mode === 'mixed' && choice === 0) return pay(u, '0.01', key(i));
  const root = `/api/boards/${u.board_id}`;
  if (choice < 3) return send(u, '/api/boards', 'boards');
  if (choice < 6) {
    const r = send(u, root + '/wallets/me', 'wallet');
    check(r, { 'correct wallet returned': x => x.status===200 && x.json('walletId')===u.wallet_id });
    return;
  }
  if (choice < 9) return send(u, root + '/transactions?page=0&size=20', 'history');
  return send(u, root, 'details');
}
export function handleSummary(data) {
  return { [__ENV.SUMMARY]: JSON.stringify(data, null, 2) };
}
