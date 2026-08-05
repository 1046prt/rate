// k6 load test for the rate limiter API.
// Usage (point RATELIMITER_API_KEY at the deployed key):
//   k6 run --env API_KEY=secret --vus 50 --duration 2m loadtest/check.js
// Exit code is non-zero if checks fail (5xx rate or latency budget exceeded).
//
// 429 responses are the limiter working as designed, not errors: the per-client
// windows are far smaller than the load each VU generates, so most requests are
// (correctly) throttled. The gate metrics are http_5xx (< 0.1%) and latency.
// Throttle ratio is reported via the http_429 metric.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const serverErrors = new Rate('http_5xx');
const throttled = new Rate('http_429');

const API_KEY = __ENV.API_KEY || '';
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ALGORITHMS = ['FIXED', 'SLIDING_LOG', 'SLIDING_COUNTER', 'TOKEN_BUCKET', 'LEAKY_BUCKET'];

export const options = {
  scenarios: {
    ramp_up: {
      executor: 'ramping-vus',
      stages: [
        { duration: '30s', target: 100 },   // warm up
        { duration: '60s', target: 100 },   // sustained load
        { duration: '30s', target: 0 },     // cool down
      ],
    },
  },
  thresholds: {
    http_5xx: ['rate<0.001'],            // < 0.1% server errors
    http_req_duration: ['p(95)<100'],    // p95 latency < 100 ms through the edge
    checks: ['rate>0.99'],
  },
};

export default function () {
  const algorithm = ALGORITHMS[__VU % ALGORITHMS.length];
  const payload = JSON.stringify({
    clientId: `load-${__VU}`,
    algorithm,
  });
  const headers = {
    'Content-Type': 'application/json',
    ...(API_KEY ? { 'X-Api-Key': API_KEY } : {}),
  };

  const res = http.post(`${BASE_URL}/api/v1/check`, payload, { headers });

  const rateLimited = res.status === 429;
  check(res, {
    'status is 200 or 429': () => res.status === 200 || rateLimited,
    'body is valid JSON': () => res.json() !== null,
    'has rate-limit header': () => Object.keys(res.headers).some((k) => k.toLowerCase() === 'x-ratelimit-limit'),
  });

  serverErrors.add(res.status >= 500 ? 1 : 0);
  throttled.add(rateLimited ? 1 : 0);

  // Pace per-VU requests to keep the burst shape realistic
  sleep(0.01);
}
