// Business-result accounting for the seckill buy endpoint (docs/phases/P0.md §6.6).
//
// Success is decided by the JSON `result` field, never by the HTTP status alone:
//   baseline / P0 / P1 (REJECT_STATUS=200): 202 -> ACCEPTED; 200 -> SOLD_OUT, ALREADY_BOUGHT
//   P2+               (REJECT_STATUS=409): 202 -> ACCEPTED; 409 -> SOLD_OUT, ALREADY_BOUGHT
// NOT_ACTIVE (and 503 UNAVAILABLE from P2) are failures and are counted under their own tag.
import http from 'k6/http';
import { Counter } from 'k6/metrics';

export const seckillResults = new Counter('seckill_results');
export const seckillMismatch = new Counter('seckill_mismatch');

// Default 409 since P2; configs that run pre-P2 code (A-baseline, P0, P1) must pass REJECT_STATUS=200.
export const REJECT_STATUS = Number(__ENV.REJECT_STATUS || 409);

// Only the statuses the contract allows count as non-failed in http_req_failed.
http.setResponseCallback(http.expectedStatuses(202, REJECT_STATUS));

const EXPECTED_STATUS = {
  ACCEPTED: 202,
  SOLD_OUT: REJECT_STATUS,
  ALREADY_BOUGHT: REJECT_STATUS,
};

export function parseResult(res) {
  try {
    const value = res.json('result');
    return typeof value === 'string' && value !== '' ? value : 'UNPARSEABLE';
  } catch (e) {
    return 'UNPARSEABLE';
  }
}

// Records one buy response; returns the business result string.
export function recordBuy(res) {
  const result = parseResult(res);
  seckillResults.add(1, { result });
  const expected = EXPECTED_STATUS[result];
  if (expected !== undefined && res.status !== expected) {
    seckillMismatch.add(1, { result, status: String(res.status) });
  } else if (expected === undefined && (res.status === 202 || res.status === REJECT_STATUS) && result !== 'NOT_ACTIVE') {
    // A success/reject status carrying an unknown or unparseable body.
    seckillMismatch.add(1, { result, status: String(res.status) });
  }
  return result;
}

export function count(data, name) {
  const m = data.metrics[name];
  return m && m.values && typeof m.values.count === 'number' ? m.values.count : 0;
}

export function resultCounts(data, scenario) {
  const out = {};
  for (const r of ['ACCEPTED', 'SOLD_OUT', 'ALREADY_BOUGHT', 'NOT_ACTIVE', 'UNAVAILABLE', 'UNPARSEABLE']) {
    const key = scenario ? `seckill_results{scenario:${scenario},result:${r}}` : `seckill_results{result:${r}}`;
    out[r] = count(data, key);
  }
  return out;
}

// Minimal stdout summary (no remote jslib imports).
export function textReport(meta, data) {
  const lines = [`run ${meta.runId} (${meta.script})`];
  for (const [k, v] of Object.entries(meta)) {
    if (k !== 'runId' && k !== 'script') lines.push(`  ${k}: ${typeof v === 'object' ? JSON.stringify(v) : v}`);
  }
  lines.push('thresholds:');
  for (const [name, m] of Object.entries(data.metrics)) {
    if (!m.thresholds) continue;
    for (const [expr, t] of Object.entries(m.thresholds)) {
      lines.push(`  ${t.ok ? 'PASS' : 'FAIL'} ${name}: ${expr}`);
    }
  }
  return lines.join('\n') + '\n';
}
