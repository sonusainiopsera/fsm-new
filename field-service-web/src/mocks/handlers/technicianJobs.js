/**
 * MSW mock fixtures for the technician job-detail and asset service-history endpoints.
 *
 * Provides fixtures for ASSIGNED, EN_ROUTE, IN_PROGRESS, ON_HOLD states with
 * distinct allowedTransitions sets, an asset history fixture, and 404/403 error cases.
 */

const HOLD_REASONS = [
  { code: 'AWAITING_PARTS',       label: 'Awaiting parts',        sortOrder: 10 },
  { code: 'CUSTOMER_UNAVAILABLE', label: 'Customer unavailable',  sortOrder: 20 },
  { code: 'AWAITING_ACCESS',      label: 'Awaiting site access',  sortOrder: 30 },
  { code: 'SAFETY_CONCERN',       label: 'Safety concern',        sortOrder: 40 },
];

function baseJob(overrides = {}) {
  return {
    id: 'tech-wo-001',
    reference: 'WO-2026-TECH-001',
    priority: 'HIGH',
    scheduledWindowStart: '2026-08-12T08:00:00Z',
    scheduledWindowEnd:   '2026-08-12T10:00:00Z',
    siteName: 'Alpha Industrial Site',
    siteAddress: '1 Industrial Way, Manchester, M1 1AA',
    siteAccessNotes: 'Gate code: 4421. Report to reception before proceeding to plant room B.',
    contactName: 'Alex Thompson',
    contactPhoneMasked: '****5309',
    assetId: 'asset-001',
    assetTag: 'HVAC-B42',
    assetDescription: 'Carrier AHU 2018',
    faultSummary: 'Unit not cooling. Fan running but compressor not engaging. Error code E4 on display.',
    faultCode: 'HV-COMP-E4',
    faultCategory: 'MECHANICAL',
    requiredCertifications: ['FGAS_CAT1'],
    expectedParts: [
      { partNumber: 'COMP-4470', name: 'Compressor relay',   quantityRequired: 1 },
      { partNumber: 'CAP-220UF', name: 'Start capacitor',    quantityRequired: 2 },
    ],
    responseDeadline:   '2026-08-12T11:00:00Z',
    resolutionDeadline: '2026-08-12T17:00:00Z',
    slaAtRisk: false,
    version: 3,
    holdReasons: [],
    ...overrides,
  };
}

/**
 * ASSIGNED state — allowedTransitions: [DEPART]
 */
export function techJobAssignedFixture() {
  return {
    status: 200,
    body: baseJob({
      state: 'ASSIGNED',
      allowedTransitions: ['DEPART'],
      holdReasons: [],
    }),
  };
}

/**
 * EN_ROUTE state — allowedTransitions: [START]
 */
export function techJobEnRouteFixture() {
  return {
    status: 200,
    body: baseJob({
      state: 'EN_ROUTE',
      allowedTransitions: ['START'],
      holdReasons: [],
    }),
  };
}

/**
 * IN_PROGRESS state — allowedTransitions: [HOLD, COMPLETE]
 * Includes hold reasons in response.
 */
export function techJobInProgressFixture() {
  return {
    status: 200,
    body: baseJob({
      state: 'IN_PROGRESS',
      allowedTransitions: ['COMPLETE', 'HOLD'],
      holdReasons: HOLD_REASONS,
    }),
  };
}

/**
 * ON_HOLD state — allowedTransitions: [RESUME]
 */
export function techJobOnHoldFixture() {
  return {
    status: 200,
    body: baseJob({
      state: 'ON_HOLD',
      allowedTransitions: ['RESUME'],
      holdReasons: [],
    }),
  };
}

/**
 * Asset service history — last 5 closed WOs for asset-001.
 */
export function assetServiceHistoryFixture() {
  return {
    status: 200,
    body: [
      {
        id: 'hist-wo-001',
        reference: 'WO-2026-001',
        faultSummary: 'Fan motor replacement — bearing failure.',
        resolvedAt: '2026-05-14T16:30:00Z',
        faultCode: 'HV-FAN-BRG',
        faultCategory: 'MECHANICAL',
      },
      {
        id: 'hist-wo-002',
        reference: 'WO-2025-482',
        faultSummary: 'Annual service and filter change.',
        resolvedAt: '2025-11-20T14:00:00Z',
        faultCode: null,
        faultCategory: null,
      },
      {
        id: 'hist-wo-003',
        reference: 'WO-2025-314',
        faultSummary: 'Refrigerant recharge. F-gas log updated.',
        resolvedAt: '2025-07-08T11:45:00Z',
        faultCode: 'HV-REFRIG',
        faultCategory: 'REFRIGERATION',
      },
    ],
  };
}

/**
 * 404 — job not found / not assigned to this technician.
 */
export function techJobNotFoundFixture() {
  return {
    status: 404,
    body: {
      status: 404,
      code: 'NOT_FOUND',
      message: 'Work order not found.',
      fieldErrors: [],
    },
  };
}
