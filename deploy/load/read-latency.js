// Steady read load on query-api, run by read-latency.sh next to this file. Each
// rate in RATES is one constant arrival rate scenario of DURATION seconds, run
// one after another; every request is, in equal thirds, the dashboard's recent
// alerts query over GraphQL, one alert by event_id over REST, or the current
// space weather over REST. A warm up of WARMUP seconds at the lowest rate runs
// first and is left out of the results. A request counts as an error unless it
// answers 200 within 4 s, and a GraphQL answer also counts as one when it
// carries errors or no alerts.
import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Rate } from 'k6/metrics';

const BASE = __ENV.BASE_URL;
const RATES = __ENV.RATES.split(',').map(Number);
const DURATION = Number(__ENV.DURATION);
const WARMUP = Number(__ENV.WARMUP);
const TIMEOUT = '4s';
const ENDPOINTS = ['graphql_alerts', 'rest_alert_by_id', 'rest_space_weather_current'];

const eventIds = new SharedArray('event ids', () => open(__ENV.IDS_FILE).split('\n').filter((id) => id !== ''));
const errors = new Rate('request_errors');

// The dashboard's RecentAlerts document (dashboard/src/app/data/queries.ts) with its page of 20, without the
// __typename fields its GraphQL client adds.
const RECENT_ALERTS = `query RecentAlerts($limit: Int!, $kind: String, $after: String) {
  alerts(limit: $limit, kind: $kind, after: $after) {
    items {
      kind
      event_id
      received_at
      space_weather_level {
        scale state derived_level derived_label previous_state previous_derived_level trigger satellite value
        unit xray_class interval_start interval_end sample_time no_data_since no_data_reason ended_by_satellite
      }
      close_approach {
        run_id
        watchlist_object { catalog_number name element_age_days }
        other_object { catalog_number name element_age_days }
        time_of_closest_approach miss_distance_m relative_speed_m_per_s
      }
      screening_run { run_id }
      acknowledgement { action acted_at principal note }
    }
    next
  }
}`;

function arrivals(rate, duration, start) {
  return {
    executor: 'constant-arrival-rate',
    rate,
    timeUnit: '1s',
    duration: `${duration}s`,
    preAllocatedVUs: Math.max(10, rate),
    maxVUs: rate * 4,
    startTime: `${start}s`,
    gracefulStop: '5s',
  };
}

const scenarios = { warmup: arrivals(Math.min(...RATES), WARMUP, 0) };
// A threshold that always holds makes the summary keep each sub metric.
const thresholds = { 'iterations{scenario:warmup}': ['count>=0'] };
RATES.forEach((rate, i) => {
  const name = `rate_${rate}`;
  scenarios[name] = arrivals(rate, DURATION, WARMUP + 5 + i * (DURATION + 5));
  thresholds[`iterations{scenario:${name}}`] = ['count>=0'];
  thresholds[`dropped_iterations{scenario:${name}}`] = ['count>=0'];
  for (const endpoint of ENDPOINTS) {
    const tags = `{scenario:${name},endpoint:${endpoint}}`;
    thresholds[`http_req_duration${tags}`] = ['max>=0'];
    thresholds[`request_errors${tags}`] = ['rate>=0'];
  }
});

export const options = {
  scenarios,
  thresholds,
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max', 'count'],
};

// The XSRF cookie is Secure, so k6 keeps it out of plain HTTP requests; the token
// is sent by hand as both the cookie and the header, as the dashboard's browser would.
// With no one signed in the session answer is 401, and it still sets the cookie.
export function setup() {
  const res = http.get(`${BASE}/api/auth/session`, { tags: { endpoint: 'session' } });
  const cookie = res.cookies['XSRF-TOKEN'];
  if (res.status !== 401 || !cookie || cookie.length === 0 || !cookie[0].value) {
    exec.test.abort(`GET /api/auth/session answered ${res.status} without an XSRF-TOKEN cookie`);
  }
  if (eventIds.length === 0) {
    exec.test.abort('no event ids to read');
  }
  return { token: cookie[0].value };
}

export default function (data) {
  const n = exec.scenario.iterationInTest;
  const endpoint = ENDPOINTS[n % 3];
  const tags = { endpoint };
  let res;
  let ok;
  if (endpoint === 'graphql_alerts') {
    res = http.post(`${BASE}/api/graphql`, JSON.stringify({ query: RECENT_ALERTS, variables: { limit: 20 } }), {
      tags,
      timeout: TIMEOUT,
      headers: {
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': data.token,
        Cookie: `XSRF-TOKEN=${data.token}`,
      },
    });
    ok = res.status === 200 && graphqlOk(res);
  } else if (endpoint === 'rest_alert_by_id') {
    const id = eventIds[(n * 7919) % eventIds.length];
    res = http.get(`${BASE}/api/alerts/by-id?event_id=${encodeURIComponent(id)}`, { tags, timeout: TIMEOUT });
    ok = res.status === 200;
  } else {
    res = http.get(`${BASE}/api/space-weather/current`, { tags, timeout: TIMEOUT });
    ok = res.status === 200;
  }
  errors.add(!ok, tags);
}

function graphqlOk(res) {
  try {
    const body = res.json();
    return !body.errors && body.data && body.data.alerts && body.data.alerts.items.length > 0;
  } catch (e) {
    return false;
  }
}

function valuesOf(data, name) {
  const metric = data.metrics[name];
  return metric ? metric.values : null;
}

export function handleSummary(data) {
  const warm = valuesOf(data, 'iterations{scenario:warmup}');
  const out = {
    rates: RATES,
    duration_s: DURATION,
    warmup: { rate_per_s: Math.min(...RATES), duration_s: WARMUP, iterations: warm ? warm.count : 0 },
    event_ids: eventIds.length,
    scenarios: [],
  };
  for (const rate of RATES) {
    const name = `rate_${rate}`;
    const done = valuesOf(data, `iterations{scenario:${name}}`);
    const dropped = valuesOf(data, `dropped_iterations{scenario:${name}}`);
    const iterations = done ? done.count : 0;
    const droppedCount = dropped ? dropped.count : 0;
    const scenario = {
      scenario: name,
      target_per_s: rate,
      iterations,
      dropped_iterations: droppedCount,
      // Arrivals neither completed nor dropped, such as iterations cut off at the graceful stop.
      unaccounted: rate * DURATION - iterations - droppedCount,
      endpoints: [],
    };
    for (const endpoint of ENDPOINTS) {
      const tags = `{scenario:${name},endpoint:${endpoint}}`;
      const d = valuesOf(data, `http_req_duration${tags}`) || {};
      const e = valuesOf(data, `request_errors${tags}`) || {};
      scenario.endpoints.push({
        endpoint,
        requests: d.count || 0,
        errors: e.passes || 0,
        error_rate: e.rate === undefined ? null : e.rate,
        p50_ms: d['p(50)'] === undefined ? null : d['p(50)'],
        p95_ms: d['p(95)'] === undefined ? null : d['p(95)'],
        p99_ms: d['p(99)'] === undefined ? null : d['p(99)'],
        max_ms: d.max === undefined ? null : d.max,
      });
    }
    out.scenarios.push(scenario);
  }
  return { [__ENV.SUMMARY_FILE]: JSON.stringify(out, null, 2) + '\n' };
}
