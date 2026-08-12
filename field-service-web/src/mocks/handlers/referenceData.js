/**
 * Mock fixtures for reference data and work-order creation (WO-131).
 *
 * Provides:
 * - Customer, site, and asset reference fixtures (valid + mismatched combos)
 * - SLA policy fixtures per priority
 * - Canned creation success, 400, 422, and 429 responses
 * - Double-submit idempotency fixture
 *
 * Usage:
 *   import { mockRespond } from './index.js';
 *   import { slaPolicyFixture } from './referenceData.js';
 *   mockRespond('GET', '/api/v1/sla-policies/by-priority/HIGH', slaPolicyFixture('HIGH'));
 */

// ---- Customers ----------------------------------------------------------

export function customersFixture() {
  return {
    status: 200,
    body: {
      data: [
        { id: 'cust-001', name: 'Acme Corp',        contactEmail: 'ops@acme.example',   active: true, createdAt: '2026-01-10T09:00:00Z' },
        { id: 'cust-002', name: 'Beta Industries',  contactEmail: 'fm@beta.example',     active: true, createdAt: '2026-02-15T11:30:00Z' },
        { id: 'cust-003', name: 'Gamma Ltd',        contactEmail: null,                  active: true, createdAt: '2026-03-01T08:00:00Z' },
      ],
      page: { number: 0, size: 50, totalElements: 3, totalPages: 1, estimated: false },
      _links: {},
    },
  };
}

// ---- Sites (by customer) ------------------------------------------------

export function sitesByCustomerFixture(customerId = 'cust-001') {
  const all = {
    'cust-001': [
      { id: 'site-001', customerId: 'cust-001', customerName: 'Acme Corp',       name: 'London HQ',  address: '1 Acme Way', active: true, createdAt: '2026-01-10T09:00:00Z' },
      { id: 'site-002', customerId: 'cust-001', customerName: 'Acme Corp',       name: 'Manchester', address: '5 North Rd', active: true, createdAt: '2026-02-01T09:00:00Z' },
    ],
    'cust-002': [
      { id: 'site-003', customerId: 'cust-002', customerName: 'Beta Industries', name: 'Site B1',    address: '22 Beta St', active: true, createdAt: '2026-02-15T11:30:00Z' },
    ],
    'cust-003': [],
  };
  return {
    status: 200,
    body: {
      data: all[customerId] ?? [],
      page: { number: 0, size: 50, totalElements: (all[customerId] ?? []).length, totalPages: 1, estimated: false },
      _links: {},
    },
  };
}

// ---- Assets (by site) ---------------------------------------------------

export function assetsBySiteFixture(siteId = 'site-001') {
  const all = {
    'site-001': [
      { id: 'asset-001', siteId: 'site-001', siteName: 'London HQ',  assetType: 'HVAC_UNIT', serialNumber: 'HV-0001', model: 'Carrier 30XW', active: true, createdAt: '2026-01-10T09:00:00Z' },
      { id: 'asset-002', siteId: 'site-001', siteName: 'London HQ',  assetType: 'BOILER',    serialNumber: 'BLR-001', model: null,           active: true, createdAt: '2026-01-10T09:00:00Z' },
    ],
    'site-002': [
      { id: 'asset-003', siteId: 'site-002', siteName: 'Manchester', assetType: 'PUMP',      serialNumber: 'PMP-42',  model: 'Grundfos 25',  active: true, createdAt: '2026-02-01T09:00:00Z' },
    ],
    'site-003': [],
  };
  return {
    status: 200,
    body: {
      data: all[siteId] ?? [],
      page: { number: 0, size: 50, totalElements: (all[siteId] ?? []).length, totalPages: 1, estimated: false },
      _links: {},
    },
  };
}

// ---- SLA policies by priority -------------------------------------------

const POLICY_BY_PRIORITY = {
  URGENT: { responseMinutes: 60,   resolutionMinutes: 240,  atRiskFraction: 0.80 },
  HIGH:   { responseMinutes: 240,  resolutionMinutes: 480,  atRiskFraction: 0.80 },
  NORMAL: { responseMinutes: 480,  resolutionMinutes: 1440, atRiskFraction: 0.80 },
  LOW:    { responseMinutes: 1440, resolutionMinutes: 4320, atRiskFraction: 0.80 },
};

/**
 * @param {'URGENT'|'HIGH'|'NORMAL'|'LOW'} priority
 */
export function slaPolicyFixture(priority) {
  const p = POLICY_BY_PRIORITY[priority];
  if (!p) return { status: 404, body: { code: 'NOT_FOUND', message: 'Policy not found' } };
  return {
    status: 200,
    body: {
      priority,
      responseMinutes:   p.responseMinutes,
      resolutionMinutes: p.resolutionMinutes,
      atRiskFraction:    p.atRiskFraction,
    },
  };
}

export function slaPolicyMissingFixture(priority) {
  return { status: 404, body: { code: 'NOT_FOUND', message: `No active SLA policy for priority ${priority}` } };
}

// ---- Work order creation ------------------------------------------------

/**
 * @param {{ reference?: string }} opts
 */
export function createWorkOrderSuccessFixture({ reference = 'WO-2026-100' } = {}) {
  return {
    status: 201,
    body: {
      id:                 'wo-new-001',
      reference,
      state:              'NEW',
      priority:           'HIGH',
      customerId:         'cust-001',
      customerName:       'Acme Corp',
      siteId:             'site-001',
      siteName:           'London HQ',
      assetId:            null,
      faultDescription:   'HVAC unit fault reported by site manager.',
      responseDeadline:   new Date(Date.now() + 4 * 60 * 60 * 1000).toISOString(),
      resolutionDeadline: new Date(Date.now() + 8 * 60 * 60 * 1000).toISOString(),
      atRisk:             false,
      legalNextEvents:    ['ASSIGN', 'CANCEL'],
      appliedSlaPolicyId: 'sla-001',
      version:            1,
      createdAt:          new Date().toISOString(),
    },
  };
}

/**
 * @param {Array<{ field: string, message: string }>} fieldErrors
 */
export function createWorkOrderFieldErrorFixture(fieldErrors = [
  { field: 'faultDescription', message: 'must be at least 10 characters' },
  { field: 'customerId',       message: 'must not be null' },
]) {
  return {
    status: 400,
    body: {
      status:      400,
      code:        'VALIDATION_FAILED',
      message:     'The request contained invalid data.',
      fieldErrors,
      traceId:     'test-trace-id-400',
    },
  };
}

export function createWorkOrderSlaMissingFixture() {
  return {
    status: 422,
    body: {
      status:      422,
      code:        'SLA_POLICY_MISSING',
      message:     'No active SLA policy is configured for the requested priority tier.',
      fieldErrors: [],
      traceId:     'test-trace-id-422',
    },
  };
}

export function createWorkOrderMismatchFixture(code = 'SITE_CUSTOMER_MISMATCH') {
  return {
    status: 422,
    body: {
      status:      422,
      code,
      message:     code === 'SITE_CUSTOMER_MISMATCH'
        ? 'The selected site does not belong to the selected customer.'
        : 'The selected asset does not belong to the selected site.',
      fieldErrors: [],
      traceId:     'test-trace-id-422m',
    },
  };
}

export function createWorkOrderRateLimitedFixture(retryAfterSeconds = 30) {
  return {
    status:  429,
    body: {
      status:      429,
      code:        'RATE_LIMITED',
      message:     'Too many requests. Please wait before trying again.',
      fieldErrors: [],
      traceId:     'test-trace-id-429',
    },
    headers: { 'Retry-After': String(retryAfterSeconds) },
  };
}
