// Throughput scenario: ramp to RATE req/s over RAMP s, then hold RATE for STEADY s.
// Every iteration is a distinct synthetic buyer, so with enough stock each buy is ACCEPTED.
// Env: BASE_URL, EVENT_ID, JWT_SECRET, RATE, RAMP, STEADY, USER_BASE, REJECT_STATUS,
//      SUMMARY_PATH, RUN_ID, K6_VERSION.
import http from 'k6/http';
import exec from 'k6/execution';
import { studentToken } from './lib/jwt.js';
import { recordBuy, count, resultCounts, textReport, REJECT_STATUS } from './lib/result.js';

const BASE_URL = __ENV.BASE_URL;
const EVENT_ID = __ENV.EVENT_ID;
const SIGNING_KEY = __ENV.JWT_SECRET;
const RATE = Number(__ENV.RATE || 10);
const RAMP = Number(__ENV.RAMP || 10);
const STEADY = Number(__ENV.STEADY || 20);
const USER_BASE = Number(__ENV.USER_BASE || 1000000000);
const STEADY_OFFSET = 50000000;
const MAX_VUS = Math.max(20, RATE * 4);

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: MAX_VUS,
      maxVUs: MAX_VUS,
      stages: [{ target: RATE, duration: `${RAMP}s` }],
    },
    steady: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${STEADY}s`,
      startTime: `${RAMP}s`,
      preAllocatedVUs: MAX_VUS,
      maxVUs: MAX_VUS,
    },
  },
  thresholds: {
    'http_reqs{scenario:steady}': ['count>0'],
    'dropped_iterations{scenario:steady}': ['count==0'],
    'seckill_results{scenario:steady,result:ACCEPTED}': ['count>0'],
    'seckill_results{scenario:steady,result:UNPARSEABLE}': ['count==0'],
    'seckill_results{scenario:steady,result:NOT_ACTIVE}': ['count==0'],
    'seckill_mismatch': ['count==0'],
    'http_req_duration{scenario:steady}': ['p(99)<1000'],
    // Reporting only (always true): makes whole-test per-result counts available to handleSummary.
    'seckill_results{result:ACCEPTED}': ['count>=0'],
    'seckill_results{result:SOLD_OUT}': ['count>=0'],
    'seckill_results{result:ALREADY_BOUGHT}': ['count>=0'],
    'seckill_results{result:NOT_ACTIVE}': ['count>=0'],
    'seckill_results{result:UNPARSEABLE}': ['count>=0'],
    'seckill_results{scenario:steady,result:SOLD_OUT}': ['count>=0'],
    'seckill_results{scenario:steady,result:ALREADY_BOUGHT}': ['count>=0'],
  },
};

export default function () {
  const steady = exec.scenario.name === 'steady';
  const userId = USER_BASE + exec.scenario.iterationInTest + (steady ? STEADY_OFFSET : 0);
  const res = http.post(`${BASE_URL}/api/seckill/${EVENT_ID}/buy`, null, {
    headers: { Authorization: `Bearer ${studentToken(userId, SIGNING_KEY)}` },
    tags: { name: 'seckill_buy' },
  });
  recordBuy(res);
}

export function handleSummary(data) {
  const steadyRequests = count(data, 'http_reqs{scenario:steady}');
  const steadyAccepted = count(data, 'seckill_results{scenario:steady,result:ACCEPTED}');
  const results = resultCounts(data);
  const meta = {
    runId: __ENV.RUN_ID,
    script: 'throughput',
    rate: RATE,
    rampSeconds: RAMP,
    steadySeconds: STEADY,
    // QPS is count / STEADY: k6's submetric `rate` divides by the whole test duration.
    requestRps: steadyRequests / STEADY,
    acceptedRps: steadyAccepted / STEADY,
    eventId: Number(EVENT_ID),
    rejectStatus: REJECT_STATUS,
    baseUrl: BASE_URL,
    k6Version: __ENV.K6_VERSION || 'unknown',
    userBase: USER_BASE,
    accepted: results.ACCEPTED,
    results,
    steadyResults: resultCounts(data, 'steady'),
    steadyRequests,
    droppedSteady: count(data, 'dropped_iterations{scenario:steady}'),
    mismatch: count(data, 'seckill_mismatch'),
  };
  const out = { stdout: textReport(meta, data) };
  out[__ENV.SUMMARY_PATH || 'summary.json'] = JSON.stringify({ meta, metrics: data.metrics }, null, 2);
  return out;
}
