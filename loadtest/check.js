// k6 load test for the rate limiter API.
// Usage (point RATELIMITER_API_KEY at the deployed key):
//   k6 run --env API_KEY=secret --vus 50 --duration 2m loadtest/check.js
// Exit code is non-zero if checks fail (error rate or latency budget exceeded).

import http from 'k6/http';
import { check, sleep } from 'k6';

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
    http_req_failed: ['rate<0.001'],     // < 0.1% error rate
    http_req_duration: ['p(95)<50'],     // p95 latency < 50 ms
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
    'has rate-limit header': () => res.headers['X-RateLimit-Limit'] !== undefined,
  });

  // Pace per-VU requests to keep the burst shape realistic
  sleep(0.01);
}
