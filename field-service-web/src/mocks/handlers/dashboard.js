/**
 * MSW-style mock fixtures and route defaults for the Operations Dashboard.
 *
 * Covers all 7 KPI widget families across 4 named states, provisional and
 * baseline-pending maturity variants, and a 90-day trend series.
 * All values are anonymized — no real personal or operational data.
 */

export const DASHBOARD_PATH = '/api/v1/operations/kpi-widgets';

// ── Trend series fixture (90-day) ─────────────────────────────────────────

const TREND_90D = {
  caption: 'SLA Compliance vs Resolution Time — 90-day trend',
  series: [
    { key: 'slaCompliance',    name: 'SLA Compliance (%)',    accent: true  },
    { key: 'resolutionTimeH',  name: 'Avg Resolution (h)',    accent: false },
  ],
  data: Array.from({ length: 90 }, (_, i) => ({
    period: `Day ${i + 1}`,
    slaCompliance:   Math.round(88 + Math.sin(i / 8) * 6),
    resolutionTimeH: parseFloat((4.5 + Math.cos(i / 10) * 1.5).toFixed(1)),
  })),
};

const TREND_30D = {
  caption: 'SLA Compliance — 30-day trend',
  series: [{ key: 'slaCompliance', name: 'SLA Compliance (%)', accent: true }],
  data: Array.from({ length: 30 }, (_, i) => ({
    period: `Day ${i + 1}`,
    slaCompliance: Math.round(90 + Math.sin(i / 5) * 4),
  })),
};

const TREND_7D = {
  caption: 'SLA Compliance — 7-day trend',
  series: [{ key: 'slaCompliance', name: 'SLA Compliance (%)', accent: true }],
  data: Array.from({ length: 7 }, (_, i) => ({
    period: `Day ${i + 1}`,
    slaCompliance: Math.round(91 + Math.sin(i / 2) * 3),
  })),
};

// ── Full settled-widget set (30d baseline) ────────────────────────────────

export const settledWidgets30d = [
  {
    id: 'sla-compliance',
    label: 'SLA Compliance',
    value: '94.2%',
    rawValue: 94.2,
    delta: 2.1,
    deltaLabel: '%pts',
    target: 95,
    current: 94.2,
    maturity: 'SETTLED',
    sparkline: [88, 90, 92, 94, 96, 93, 95, 94],
    degraded: false,
    dataAge: new Date(Date.now() - 4 * 60 * 1000).toISOString(),
    unit: '%',
    notMeaningfulReason: null,
  },
  {
    id: 'resolution-time',
    label: 'Avg Resolution Time',
    value: '4.2h',
    rawValue: 4.2,
    delta: -0.3,
    deltaLabel: 'h',
    target: null,
    current: null,
    maturity: 'SETTLED',
    sparkline: [5.1, 4.8, 4.5, 4.3, 4.0, 4.2, 4.2],
    degraded: false,
    dataAge: new Date(Date.now() - 4 * 60 * 1000).toISOString(),
    unit: 'h',
    notMeaningfulReason: null,
  },
  {
    id: 'utilisation',
    label: 'Technician Utilisation',
    value: '78%',
    rawValue: 78,
    delta: 3,
    deltaLabel: '%pts',
    target: 80,
    current: 78,
    maturity: 'SETTLED',
    sparkline: [70, 72, 74, 76, 78, 77, 78],
    degraded: false,
    dataAge: new Date(Date.now() - 4 * 60 * 1000).toISOString(),
    unit: '%',
    notMeaningfulReason: null,
  },
  {
    id: 'jobs-per-day',
    label: 'Jobs per Day',
    value: '12.4',
    rawValue: 12.4,
    delta: 0.8,
    deltaLabel: 'jobs',
    target: null,
    current: null,
    maturity: 'SETTLED',
    sparkline: [11, 11.5, 12, 12.2, 12.4, 12.3, 12.4],
    degraded: false,
    dataAge: new Date(Date.now() - 4 * 60 * 1000).toISOString(),
    unit: null,
    notMeaningfulReason: null,
  },
  {
    id: 'first-time-fix',
    label: 'First-Time Fix Rate',
    value: '82%',
    rawValue: 82,
    delta: 1.5,
    deltaLabel: '%pts',
    target: 85,
    current: 82,
    maturity: 'PROVISIONAL',
    sparkline: [78, 79, 81, 82, 83, 81, 82],
    degraded: false,
    dataAge: new Date(Date.now() - 4 * 60 * 1000).toISOString(),
    unit: '%',
    notMeaningfulReason: null,
  },
  {
    id: 'backlog',
    label: 'Open Backlog',
    value: '34',
    rawValue: 34,
    delta: -5,
    deltaLabel: 'jobs',
    target: null,
    current: null,
    maturity: 'SETTLED',
    sparkline: [42, 40, 38, 36, 34, 35, 34],
    degraded: false,
    dataAge: new Date(Date.now() - 4 * 60 * 1000).toISOString(),
    unit: null,
    notMeaningfulReason: null,
  },
  {
    id: 'workload-balance',
    label: 'Workload Balance',
    value: '—',
    rawValue: null,
    delta: null,
    deltaLabel: null,
    target: null,
    current: null,
    maturity: 'NOT_MEANINGFUL',
    sparkline: null,
    degraded: false,
    dataAge: new Date(Date.now() - 4 * 60 * 1000).toISOString(),
    unit: null,
    notMeaningfulReason: 'Fewer than 5 active technicians — workload balance is not meaningful for this team size.',
  },
];

// ── Degraded variant — last known values with older dataAge ───────────────

export const degradedWidgets = settledWidgets30d.map((w) => ({
  ...w,
  degraded: true,
  dataAge: new Date(Date.now() - 62 * 60 * 1000).toISOString(), // 62 min ago
}));

// ── Baseline-pending variant ──────────────────────────────────────────────

export const baselinePendingWidgets = settledWidgets30d.map((w) =>
  w.id === 'first-time-fix'
    ? { ...w, maturity: 'BASELINE_PENDING', target: null, current: null }
    : w,
);

// ── Full response envelopes ───────────────────────────────────────────────

/**
 * Settled 30d fixture — all widgets healthy.
 * @returns {{ status: number, body: unknown, headers: Record<string, string> }}
 */
export function dashboardSettled30dFixture() {
  return {
    status: 200,
    body: {
      meta: {
        window: '30d',
        generatedAt: new Date().toISOString(),
        degraded: false,
        allDegraded: false,
      },
      widgets: settledWidgets30d,
      trend: TREND_30D,
      _etag: '"dashboard-etag-30d-v1"',
    },
    headers: { 'ETag': '"dashboard-etag-30d-v1"' },
  };
}

/**
 * Settled 7d fixture.
 */
export function dashboardSettled7dFixture() {
  return {
    status: 200,
    body: {
      meta: {
        window: '7d',
        generatedAt: new Date().toISOString(),
        degraded: false,
        allDegraded: false,
      },
      widgets: settledWidgets30d.map((w) => ({
        ...w,
        sparkline: w.sparkline ? w.sparkline.slice(-7) : null,
      })),
      trend: TREND_7D,
      _etag: '"dashboard-etag-7d-v1"',
    },
    headers: { 'ETag': '"dashboard-etag-7d-v1"' },
  };
}

/**
 * 90d fixture with extended trend — used by perf harness.
 */
export function dashboardSettled90dFixture() {
  return {
    status: 200,
    body: {
      meta: {
        window: '90d',
        generatedAt: new Date().toISOString(),
        degraded: false,
        allDegraded: false,
      },
      widgets: settledWidgets30d,
      trend: TREND_90D,
      _etag: '"dashboard-etag-90d-v1"',
    },
    headers: { 'ETag': '"dashboard-etag-90d-v1"' },
  };
}

/**
 * All-degraded fixture — dashboard-level notice + stale cards.
 */
export function dashboardAllDegradedFixture() {
  return {
    status: 200,
    body: {
      meta: {
        window: '30d',
        generatedAt: new Date(Date.now() - 62 * 60 * 1000).toISOString(),
        degraded: true,
        allDegraded: true,
      },
      widgets: degradedWidgets,
      trend: null,
      _etag: '"dashboard-etag-degraded-v1"',
    },
    headers: { 'ETag': '"dashboard-etag-degraded-v1"' },
  };
}

/**
 * Baseline-pending fixture — first-time fix card shows BASELINE_PENDING.
 */
export function dashboardBaselinePendingFixture() {
  return {
    status: 200,
    body: {
      meta: {
        window: '30d',
        generatedAt: new Date().toISOString(),
        degraded: false,
        allDegraded: false,
      },
      widgets: baselinePendingWidgets,
      trend: TREND_30D,
      _etag: '"dashboard-etag-baseline-v1"',
    },
    headers: { 'ETag': '"dashboard-etag-baseline-v1"' },
  };
}

/**
 * 304 Not Modified response (ETag unchanged).
 */
export function dashboard304Fixture() {
  return {
    status: 304,
    body: null,
    headers: { 'ETag': '"dashboard-etag-30d-v1"' },
  };
}

/**
 * 503 Service Unavailable.
 */
export function dashboardUnavailableFixture() {
  return {
    status: 503,
    body: {
      status: 503,
      code: 'SERVICE_UNAVAILABLE',
      message: 'The KPI service is temporarily unavailable.',
      fieldErrors: [],
      traceId: 'test-trace-dashboard-001',
    },
  };
}

/**
 * Empty widgets list (new environment, no data yet).
 */
export function dashboardEmptyFixture() {
  return {
    status: 200,
    body: {
      meta: {
        window: '30d',
        generatedAt: new Date().toISOString(),
        degraded: false,
        allDegraded: false,
      },
      widgets: [],
      trend: null,
      _etag: '"dashboard-etag-empty-v1"',
    },
    headers: { 'ETag': '"dashboard-etag-empty-v1"' },
  };
}

/**
 * Default route entry used in handlers/index.js for the KPI widgets endpoint.
 */
export const dashboardDefaultRoute = {
  'GET:/api/v1/operations/kpi-widgets': dashboardSettled30dFixture(),
};
