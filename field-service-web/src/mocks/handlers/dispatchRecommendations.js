/**
 * MSW fixtures for the dispatch recommendations endpoint.
 *
 * GET /api/v1/work-orders/{workOrderId}/recommendations
 *
 * Exports:
 *   WO_REC_ID               — stable work-order ID used across all fixtures
 *   recommendationsPage1Fixture()
 *   recommendationsPage2Fixture()
 *   recommendationsZeroFixture()
 *   recommendationsDegradedFixture()
 *   recommendations403Fixture()
 *   recommendations422Fixture()
 *   recommendations503Fixture()
 */

export const WO_REC_ID = 'wo-recs-001';

const SNAPSHOT_ID = '00000000-0000-7140-8000-000000000001';
const GENERATED_AT = '2026-08-12T10:00:00Z';

const BASE_META = {
  snapshotId: SNAPSHOT_ID,
  generatedAt: GENERATED_AT,
  weightSetVersion: '2.1',
  travelEstimateDegraded: false,
  partsDataDegraded: false,
  candidatePoolSize: 25,
  truncated: false,
  exclusionSummary: [
    { reason: 'CERTIFICATION_EXPIRED', count: 3 },
    { reason: 'OUT_OF_REACH', count: 2 },
  ],
  partsWarnings: [],
};

function makeFactors(rank) {
  return [
    {
      factorCode: 'TRAVEL_EFFICIENCY',
      rawValue: 25 + rank * 5,
      normalisedValue: Math.max(0.1, 0.92 - (rank - 1) * 0.07),
      weight: 0.35,
      weightedContribution: 0.35 * Math.max(0.1, 0.92 - (rank - 1) * 0.07),
      explanation: `${25 + rank * 5} minutes estimated travel time from current location.`,
      degraded: false,
      stale: false,
    },
    {
      factorCode: 'PARTS_AVAILABILITY',
      rawValue: 1,
      normalisedValue: 1.0,
      weight: 0.30,
      weightedContribution: 0.30,
      explanation: 'All required parts available on vehicle.',
      degraded: false,
      stale: false,
    },
    {
      factorCode: 'CERTIFICATION_MATCH',
      rawValue: 1,
      normalisedValue: 1.0,
      weight: 0.25,
      weightedContribution: 0.25,
      explanation: 'All required certifications are current and valid.',
      degraded: false,
      stale: false,
    },
    {
      factorCode: 'WORKLOAD_BALANCE',
      rawValue: rank,
      normalisedValue: Math.max(0.2, 0.85 - (rank - 1) * 0.06),
      weight: 0.10,
      weightedContribution: 0.10 * Math.max(0.2, 0.85 - (rank - 1) * 0.06),
      explanation: `${rank} job(s) currently scheduled today.`,
      degraded: false,
      stale: false,
    },
  ];
}

/**
 * @param {number} rank
 * @param {Partial<import('./types').CandidateDto>} [overrides]
 */
function makeCandidate(rank, overrides = {}) {
  return {
    technicianId: `tech-rec-${String(rank).padStart(3, '0')}`,
    technicianName: `Technician ${rank}`,
    rank,
    score: Math.max(0.3, 0.95 - (rank - 1) * 0.05),
    travelEstimateDegraded: false,
    factors: makeFactors(rank),
    ...overrides,
  };
}

/** Full first page (3 candidates, hasNext true, points to cursor-page-2). */
export function recommendationsPage1Fixture() {
  return {
    status: 200,
    body: {
      data: [makeCandidate(1), makeCandidate(2), makeCandidate(3)],
      page: { size: 3, hasNext: true },
      links: {
        next: `/api/v1/work-orders/${WO_REC_ID}/recommendations?size=3&cursor=cursor-page-2`,
      },
      meta: BASE_META,
    },
  };
}

/** Second cursor page (ranks 4–5, hasNext false). */
export function recommendationsPage2Fixture() {
  return {
    status: 200,
    body: {
      data: [makeCandidate(4), makeCandidate(5)],
      page: { size: 2, hasNext: false },
      links: { next: null },
      meta: BASE_META,
    },
  };
}

/** Zero candidates with exclusion summary. */
export function recommendationsZeroFixture() {
  return {
    status: 200,
    body: {
      data: [],
      page: { size: 0, hasNext: false },
      links: { next: null },
      meta: {
        ...BASE_META,
        candidatePoolSize: 5,
        exclusionSummary: [
          { reason: 'CERTIFICATION_EXPIRED', count: 4 },
          { reason: 'OUT_OF_REACH', count: 1 },
        ],
      },
    },
  };
}

/** Two candidates with degraded travel estimates (per-row and aggregate). */
export function recommendationsDegradedFixture() {
  return {
    status: 200,
    body: {
      data: [
        makeCandidate(1, {
          travelEstimateDegraded: true,
          factors: makeFactors(1).map((f, i) =>
            i === 0 ? { ...f, degraded: true, explanation: 'Travel estimate only — live data unavailable.' } : f,
          ),
        }),
        makeCandidate(2, { travelEstimateDegraded: true }),
      ],
      page: { size: 2, hasNext: false },
      links: { next: null },
      meta: { ...BASE_META, travelEstimateDegraded: true },
    },
  };
}

/** 403 Forbidden. */
export function recommendations403Fixture() {
  return {
    status: 403,
    body: {
      status: 403,
      code: 'FORBIDDEN',
      message: 'Forbidden.',
      fieldErrors: [],
      traceId: 'trace-rec-403',
    },
  };
}

/** 422 work order not assignable. */
export function recommendations422Fixture() {
  return {
    status: 422,
    body: {
      status: 422,
      code: 'WORK_ORDER_NOT_ASSIGNABLE',
      message: 'This work order is already assigned.',
      fieldErrors: [],
      traceId: 'trace-rec-422',
    },
  };
}

/** 503 provider degraded (eligibility unavailable). */
export function recommendations503Fixture() {
  return {
    status: 503,
    body: {
      status: 503,
      code: 'PROVIDER_DEGRADED',
      message: 'Eligibility data temporarily unavailable.',
      fieldErrors: [],
      traceId: 'trace-rec-503',
    },
  };
}
