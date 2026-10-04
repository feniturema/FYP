// Contention scenario: BUYERS distinct synthetic users (VUS concurrent) race for STOCK units.
// Exactly STOCK must be ACCEPTED and BUYERS - STOCK SOLD_OUT; nobody may be ALREADY_BOUGHT.
// Env: BASE_URL, EVENT_ID, JWT_SECRET, STOCK, BUYERS, VUS, USER_BASE, REJECT_STATUS,
//      SUMMARY_PATH, RUN_ID, K6_VERSION.
import http from 'k6/http';
import exec from 'k6/execution';
import { studentToken } from './lib/jwt.js';
import { recordBuy, count, resultCounts, textReport, REJECT_STATUS } from './lib/result.js';

const BASE_URL = __ENV.BASE_URL;
const EVENT_ID = __ENV.EVENT_ID;
const JWT_SECRET = __ENV.JWT_SECRET;
const STOCK = Number(__ENV.STOCK);
const BUYERS = Number(__ENV.BUYERS);
const VUS = Number(__ENV.VUS || BUYERS);
const USER_BASE = Number(__ENV.USER_BASE || 1000000000);

export const options = {
  scenarios: {
    contention: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: BUYERS,
      maxDuration: '60s',
    },
  },
  thresholds: {
    'seckill_results{result:ACCEPTED}': [`count==${STOCK}`],
    'seckill_results{result:SOLD_OUT}': [`count==${BUYERS - STOCK}`],
    'seckill_results{result:ALREADY_BOUGHT}': ['count==0'],
    'seckill_mismatch': ['count==0'],
    'http_req_failed': ['rate==0'],
    // Reporting only (always true).
    'seckill_results{result:NOT_ACTIVE}': ['count>=0'],
    'seckill_results{result:UNPARSEABLE}': ['count>=0'],
  },
};

export default function () {
  const userId = USER_BASE + exec.scenario.iterationInTest;
  const res = http.post(`${BASE_URL}/api/seckill/${EVENT_ID}/buy`, null, {
    headers: { Authorization: `Bearer ${studentToken(userId, JWT_SECRET)}` },
    tags: { name: 'seckill_buy' },
  });
  recordBuy(res);
}

export function handleSummary(data) {
  const results = resultCounts(data);
  const meta = {
    runId: __ENV.RUN_ID,
    script: 'contention',
    stock: STOCK,
    buyers: BUYERS,
    vus: VUS,
    eventId: Number(EVENT_ID),
    rejectStatus: REJECT_STATUS,
    baseUrl: BASE_URL,
    k6Version: __ENV.K6_VERSION || 'unknown',
    userBase: USER_BASE,
    accepted: results.ACCEPTED,
    results,
    mismatch: count(data, 'seckill_mismatch'),
    requests: count(data, 'http_reqs'),
  };
  const out = { stdout: textReport(meta, data) };
  out[__ENV.SUMMARY_PATH || 'summary.json'] = JSON.stringify({ meta, metrics: data.metrics }, null, 2);
  return out;
}
