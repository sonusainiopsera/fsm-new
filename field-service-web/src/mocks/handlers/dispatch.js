/**
 * @fileoverview MSW-style mock fetch handlers for the dispatch recommendations endpoint.
 *
 * Endpoint mocked:
 *   GET /api/v1/work-orders/:workOrderId/recommendations[?cursor=...]
 *
 * Scenarios:
 *   - 'page1'       Full first page (3 candidates for test simplicity, hasNext true)
 *   - 'page2'       Second cursor page (2 more candidates, hasNext false)
 *   - 'empty'       Zero candidates with exclusion summary
 *   - 'degraded'    Candidates with travelEstimateDegraded / partsDataDegraded flags
 *   - 'parts'       Candidates with parts shortage warning
 *   - 'forbidden'   403 permission denied
 *   - 'notAssignable' 422 business guard refusal
 *   - 'rateLimited' 429
 *   - 'unavailable' 503 service unavailable
 *
 * Usage in tests:
 *   vi.stubGlobal('fetch', createDispatchFetch({ scenario: 'page1' }))
 */

const WORK_ORDER_ID = 'wo-dispatch-001'
const BASE = '/api/v1'

// ── Fixtures ──────────────────────────────────────────────────────────────────

const FACTORS_ALICE = [
  {
    factorCode: 'TRAVEL_TIME',
    rawValue: 22,
    normalisedValue: 0.88,
    weight: 0.35,
    weightedContribution: 0.308,
    explanation: '22 minutes estimated drive time based on current traffic.',
    degraded: false,
  },
  {
    factorCode: 'CERTIFICATION_MATCH',
    rawValue: 1.0,
    normalisedValue: 1.0,
    weight: 0.30,
    weightedContribution: 0.30,
    explanation: 'Holds all required certifications: ELEC-L2, HV-SAFE.',
    degraded: false,
  },
  {
    factorCode: 'AVAILABILITY',
    rawValue: 1.0,
    normalisedValue: 1.0,
    weight: 0.25,
    weightedContribution: 0.25,
    explanation: 'Available for the full scheduled window.',
    degraded: false,
  },
  {
    factorCode: 'WORKLOAD',
    rawValue: 2,
    normalisedValue: 0.80,
    weight: 0.10,
    weightedContribution: 0.08,
    explanation: '2 open jobs — within recommended workload.',
    degraded: false,
  },
]

const FACTORS_BOB = [
  {
    factorCode: 'TRAVEL_TIME',
    rawValue: 35,
    normalisedValue: 0.70,
    weight: 0.35,
    weightedContribution: 0.245,
    explanation: '35 minutes estimated drive time.',
    degraded: false,
  },
  {
    factorCode: 'CERTIFICATION_MATCH',
    rawValue: 1.0,
    normalisedValue: 1.0,
    weight: 0.30,
    weightedContribution: 0.30,
    explanation: 'Holds all required certifications.',
    degraded: false,
  },
  {
    factorCode: 'AVAILABILITY',
    rawValue: 0.9,
    normalisedValue: 0.90,
    weight: 0.25,
    weightedContribution: 0.225,
    explanation: 'Available for most of the window.',
    degraded: false,
  },
  {
    factorCode: 'WORKLOAD',
    rawValue: 3,
    normalisedValue: 0.70,
    weight: 0.10,
    weightedContribution: 0.07,
    explanation: '3 open jobs.',
    degraded: false,
  },
]

const FACTORS_CAROL = [
  {
    factorCode: 'TRAVEL_TIME',
    rawValue: 48,
    normalisedValue: 0.55,
    weight: 0.35,
    weightedContribution: 0.1925,
    explanation: '48 minutes estimated drive time (estimated — live data unavailable).',
    degraded: true,
  },
  {
    factorCode: 'CERTIFICATION_MATCH',
    rawValue: 1.0,
    normalisedValue: 1.0,
    weight: 0.30,
    weightedContribution: 0.30,
    explanation: 'Holds all required certifications.',
    degraded: false,
  },
  {
    factorCode: 'AVAILABILITY',
    rawValue: 1.0,
    normalisedValue: 1.0,
    weight: 0.25,
    weightedContribution: 0.25,
    explanation: 'Available for the full window.',
    degraded: false,
  },
  {
    factorCode: 'WORKLOAD',
    rawValue: 1,
    normalisedValue: 0.95,
    weight: 0.10,
    weightedContribution: 0.095,
    explanation: '1 open job — very low workload.',
    degraded: false,
  },
]

/** Full first page of candidates. */
export const CANDIDATES_PAGE1 = [
  {
    technicianId: 'tech-alice-001',
    technicianName: 'Alice Nakamura',
    rank: 1,
    score: 0.938,
    factors: FACTORS_ALICE,
    travelEstimateDegraded: false,
    partsAvailability: { status: 'AVAILABLE', satisfactionRatio: 1.0, shortfalls: [] },
  },
  {
    technicianId: 'tech-bob-002',
    technicianName: 'Bob Okafor',
    rank: 2,
    score: 0.840,
    factors: FACTORS_BOB,
    travelEstimateDegraded: false,
    partsAvailability: {
      status: 'PARTIAL',
      satisfactionRatio: 0.75,
      shortfalls: [
        { partId: 'part-1', partNumber: 'C12-GASKET', requested: 4, onHand: 3, shortfall: 1 },
      ],
    },
  },
  {
    technicianId: 'tech-carol-003',
    technicianName: 'Carol Singh',
    rank: 3,
    score: 0.837,
    factors: FACTORS_CAROL,
    travelEstimateDegraded: true,
    partsAvailability: { status: 'AVAILABLE', satisfactionRatio: 1.0, shortfalls: [] },
  },
]

/** Second cursor page (different technicians, hasNext false). */
export const CANDIDATES_PAGE2 = [
  {
    technicianId: 'tech-david-004',
    technicianName: 'David Kim',
    rank: 4,
    score: 0.791,
    factors: FACTORS_ALICE.map((f) => ({ ...f, normalisedValue: f.normalisedValue * 0.85 })),
    travelEstimateDegraded: false,
    partsAvailability: { status: 'AVAILABLE', satisfactionRatio: 1.0, shortfalls: [] },
  },
  {
    technicianId: 'tech-elena-005',
    technicianName: 'Elena Rossi',
    rank: 5,
    score: 0.744,
    factors: FACTORS_BOB.map((f) => ({ ...f, normalisedValue: f.normalisedValue * 0.80 })),
    travelEstimateDegraded: false,
    partsAvailability: { status: 'AVAILABLE', satisfactionRatio: 1.0, shortfalls: [] },
  },
]

const META_NORMAL = {
  snapshotId: 'snap-001',
  generatedAt: '2026-08-12T10:00:00Z',
  weightSetVersion: 'v2',
  travelEstimateDegraded: false,
  partsDataDegraded: false,
  candidatePoolSize: 5,
  truncated: false,
  exclusionSummary: [],
  partsWarning: null,
}

const META_DEGRADED = {
  ...META_NORMAL,
  travelEstimateDegraded: true,
  partsDataDegraded: true,
}

const META_PARTS_WARNING = {
  ...META_NORMAL,
  partsWarning: {
    code: 'PARTS_UNAVAILABLE',
    shortfalls: [
      { partId: 'part-1', partNumber: 'C12-GASKET', requested: 4, onHand: 3, shortfall: 1 },
    ],
  },
}

const PAGE1_RESPONSE = {
  data: CANDIDATES_PAGE1,
  page: { size: 3, hasNext: true },
  links: { next: `/api/v1/work-orders/${WORK_ORDER_ID}/recommendations?cursor=cursor-page2&size=3` },
  meta: META_NORMAL,
}

const PAGE2_RESPONSE = {
  data: CANDIDATES_PAGE2,
  page: { size: 2, hasNext: false },
  links: { next: null },
  meta: META_NORMAL,
}

const EMPTY_RESPONSE = {
  data: [],
  page: { size: 0, hasNext: false },
  links: { next: null },
  meta: {
    ...META_NORMAL,
    candidatePoolSize: 8,
    exclusionSummary: [
      { reason: 'CERTIFICATION_EXPIRED', count: 4 },
      { reason: 'UNAVAILABLE_IN_WINDOW', count: 3 },
      { reason: 'OUT_OF_REACH', count: 1 },
    ],
  },
}

const DEGRADED_RESPONSE = {
  ...PAGE1_RESPONSE,
  data: CANDIDATES_PAGE1.map((c) => ({ ...c, travelEstimateDegraded: true })),
  meta: META_DEGRADED,
}

const PARTS_RESPONSE = {
  ...PAGE1_RESPONSE,
  meta: META_PARTS_WARNING,
}

// ── Error fixtures ────────────────────────────────────────────────────────────

const ERROR_403 = {
  status: 403,
  body: {
    code: 'FORBIDDEN',
    message: 'Access denied.',
    fieldErrors: [],
    traceId: 'trace-403-dispatch',
  },
}

const ERROR_422 = {
  status: 422,
  body: {
    code: 'WORK_ORDER_GUARD_REFUSED',
    message: 'This work order is already assigned and cannot be re-recommended.',
    fieldErrors: [],
    traceId: 'trace-422-dispatch',
  },
}

const ERROR_429 = {
  status: 429,
  body: {
    code: 'RATE_LIMITED',
    message: 'Too many requests.',
    fieldErrors: [],
    traceId: 'trace-429-dispatch',
  },
}

const ERROR_503 = {
  status: 503,
  body: {
    code: 'SERVICE_UNAVAILABLE',
    message: 'Dispatch eligibility data is temporarily unavailable. Please retry.',
    fieldErrors: [],
    traceId: 'trace-503-dispatch',
  },
}

// ── Handler factory ───────────────────────────────────────────────────────────

const RECOMMENDATIONS_RE =
  /\/api\/v1\/work-orders\/([^/?]+)\/recommendations(\?.*)?$/

/**
 * Creates a mock fetch for dispatch recommendation endpoints.
 *
 * @param {{
 *   scenario?: 'page1' | 'page2' | 'empty' | 'degraded' | 'parts' |
 *              'forbidden' | 'notAssignable' | 'rateLimited' | 'unavailable'
 * }} [opts]
 * @returns {(url: string, init?: RequestInit) => Promise<Response>}
 */
export function createDispatchFetch({ scenario = 'page1' } = {}) {
  return async function mockFetch(url, _init = {}) {
    const match = RECOMMENDATIONS_RE.exec(url)
    if (!match) {
      return jsonResponse({ code: 'NOT_MOCKED', message: `No mock for GET ${url}` }, 501)
    }

    const qs = match[2] ?? ''
    const cursor = new URLSearchParams(qs.replace(/^\?/, '')).get('cursor')

    switch (scenario) {
      case 'forbidden':      return jsonResponse(ERROR_403.body, 403)
      case 'notAssignable':  return jsonResponse(ERROR_422.body, 422)
      case 'rateLimited':    return jsonResponse(ERROR_429.body, 429)
      case 'unavailable':    return jsonResponse(ERROR_503.body, 503)
      case 'empty':          return jsonResponse(EMPTY_RESPONSE)
      case 'degraded':       return jsonResponse(DEGRADED_RESPONSE)
      case 'parts':          return jsonResponse(PARTS_RESPONSE)
      case 'page2':          return jsonResponse(PAGE2_RESPONSE)
      case 'page1':
      default:
        // Serve page2 when the cursor param is present
        return cursor ? jsonResponse(PAGE2_RESPONSE) : jsonResponse(PAGE1_RESPONSE)
    }
  }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function jsonResponse(body, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: {
      get: (h) => (h.toLowerCase() === 'content-type' ? 'application/json' : null),
    },
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
    clone() { return jsonResponse(body, status) },
  })
}

export { WORK_ORDER_ID }
