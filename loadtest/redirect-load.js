// k6 load test for the URL shortener's read-heavy redirect path.
//
// Run (app + deps must be up: docker compose up -d && mvn spring-boot:run):
//   k6 run loadtest/redirect-load.js
//
// setup() creates a short code once, then every VU hammers the redirect for it.
// Override the target with -e BASE_URL=... if the app isn't on localhost:8080.

import http from 'k6/http';
import { check, fail } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  stages: [
    { duration: '30s', target: 20 },
    { duration: '1m',  target: 50 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],
    'checks{check:status is 302 or 429}': ['rate>0.99'],
  },
};

// Create one short code and hand it to every VU.
export function setup() {
  const res = http.post(`${BASE}/api/v1/shorten`,
    JSON.stringify({ url: 'https://example.com/a/very/long/path' }),
    { headers: { 'Content-Type': 'application/json' } });
  if (res.status !== 201) {
    fail(`seed failed: expected 201, got ${res.status} — is the app running on ${BASE}?`);
  }
  return { shortCode: res.json('shortCode') };
}

export default function (data) {
  // redirects: 0 so k6 measures our own redirect latency, not the followed target.
  const res = http.get(`${BASE}/${data.shortCode}`, { redirects: 0 });
  check(res, {
    'status is 302 or 429': (r) => r.status === 302 || r.status === 429,
    'redirect has Location':  (r) => r.status !== 302 || !!r.headers['Location'],
  });
}
