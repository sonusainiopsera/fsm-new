/**
 * Mock HTTP handlers for the data-layer test suite.
 *
 * Provides fetch intercepts for every HTTP status code in the platform
 * contract, paginated collections, ETag/304 responses, SSE stream tickets,
 * and a scripted SSE event stream.
 *
 * Usage in tests:
 *   import { installHandlers, resetHandlers } from '../mocks/handlers/index.js';
 *   beforeAll(() => installHandlers());
 *   afterEach(() => resetHandlers());
 *
 * Extend per test with: mockHandlers.respond(method, path, override).
 */

const BASE = '/api/v1';

/** @type {Map<string, { status: number, body: unknown, headers?: Record<string, string> }>} */
const _overrides = new Map();

/** Known ETags per path. */
const _etags = new Map();

/**
 * Installs the global fetch intercept.
 * Call once per test suite (beforeAll).
 */
export function installHandlers() {
  globalThis._originalFetch = globalThis.fetch;
  globalThis.fetch = mockFetch;
}

/**
 * Removes all per-test overrides but keeps the intercept active.
 * Call in afterEach.
 */
export function resetHandlers() {
  _overrides.clear();
}

/**
 * Restores the original fetch.
 * Call in afterAll.
 */
export function uninstallHandlers() {
  if (globalThis._originalFetch) {
    globalThis.fetch = globalThis._originalFetch;
    delete globalThis._originalFetch;
  }
}

/**
 * Registers a one-shot override for a specific method+path.
 * @param {string} method
 * @param {string} path   Full path including /api/v1
 * @param {{ status: number, body: unknown, headers?: Record<string, string> }} response
 */
export function mockRespond(method, path, response) {
  _overrides.set(`${method.toUpperCase()}:${path}`, response);
}

/**
 * Pre-registers an ETag for a path so conditional requests return 304.
 * @param {string} path
 * @param {string} etag
 */
export function seedEtag(path, etag) {
  _etags.set(path, etag);
}

// ---- Default route table ------------------------------------------------

// Mock access token — header.payload.signature where payload decodes to user claims.
// Payload JSON: {"sub":"user-001","roles":["ADMIN"],"displayName":"Admin User","exp":9999999999}
const MOCK_ACCESS_TOKEN =
  'eyJhbGciOiJSUzI1NiJ9.' +
  'eyJzdWIiOiJ1c2VyLTAwMSIsInJvbGVzIjpbIkFETUlOIl0sImRpc3BsYXlOYW1lIjoiQWRtaW4gVXNlciIsImV4cCI6OTk5OTk5OTk5OX0.' +
  'fakesig';

// Refreshed token — same payload, different signature marker.
const MOCK_REFRESHED_TOKEN =
  'eyJhbGciOiJSUzI1NiJ9.' +
  'eyJzdWIiOiJ1c2VyLTAwMSIsInJvbGVzIjpbIkFETUlOIl0sImRpc3BsYXlOYW1lIjoiQWRtaW4gVXNlciIsImV4cCI6OTk5OTk5OTk5OX0.' +
  'refreshedsig';

const DEFAULT_ROUTES = {
  // Auth — 200 success
  'POST:/api/v1/auth/login': {
    status: 200,
    body: {
      accessToken: MOCK_ACCESS_TOKEN,
      tokenType: 'Bearer',
      expiresIn: 900,
      user: {
        id: 'user-001',
        displayName: 'Admin User',
        roles: ['ADMIN'],
      },
    },
    headers: { 'Set-Cookie': 'refresh_token=mock-refresh; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth' },
  },
  'POST:/api/v1/auth/refresh': {
    status: 200,
    body: { accessToken: MOCK_REFRESHED_TOKEN, expiresIn: 900 },
  },
  'POST:/api/v1/auth/logout': {
    status: 204,
    body: null,
  },
  'POST:/api/v1/auth/stream-ticket': {
    status: 200,
    body: { ticket: 'mock-stream-ticket-abc123', expiresIn: 60 },
  },

  // Work orders paginated list
  'GET:/api/v1/work-orders': {
    status: 200,
    body: {
      data: [
        { id: 'wo-001', reference: 'WO-001', state: 'IN_PROGRESS', priority: 'HIGH', atRisk: true },
        { id: 'wo-002', reference: 'WO-002', state: 'NEW',         priority: 'NORMAL', atRisk: false },
      ],
      page: { number: 0, size: 20, totalElements: 2, totalPages: 1, estimated: false },
      _links: { self: '/api/v1/work-orders?page=0&size=20', next: null, prev: null },
    },
    headers: { 'ETag': '"mock-etag-v1"' },
  },

  // Inventory stock positions
  'GET:/api/v1/inventory/stock': {
    status: 200,
    body: {
      data: [
        {
          id: 'sp-001', partId: 'part-001', partNumber: 'FLT-2890',
          partDescription: 'Oil Filter — Heavy Duty', locationId: 'loc-wh-001',
          locationName: 'Main Warehouse', locationType: 'WAREHOUSE',
          quantityOnHand: 45, reorderPoint: 20, stockStatus: 'OK',
          asOf: '2026-08-11T10:00:00Z',
        },
        {
          id: 'sp-002', partId: 'part-002', partNumber: 'BRK-1040',
          partDescription: 'Brake Pad Set — Front', locationId: 'loc-wh-001',
          locationName: 'Main Warehouse', locationType: 'WAREHOUSE',
          quantityOnHand: 8, reorderPoint: 10, stockStatus: 'LOW',
          asOf: '2026-08-11T10:00:00Z',
        },
        {
          id: 'sp-003', partId: 'part-003', partNumber: 'HVA-0055',
          partDescription: 'HVAC Refrigerant R-410A (Can)', locationId: 'loc-van-001',
          locationName: 'Van 12 — J. Smith', locationType: 'VEHICLE',
          quantityOnHand: 0, reorderPoint: 2, stockStatus: 'OUT',
          asOf: '2026-08-11T10:00:00Z',
        },
      ],
      page: { number: 0, size: 50, totalElements: 3, totalPages: 1, estimated: false },
      _links: { self: '/api/v1/inventory/stock?page=0&size=50', next: null, prev: null },
      asOf: '2026-08-11T10:00:00Z',
    },
    headers: { 'ETag': '"stock-etag-v1"' },
  },

  // Inventory alerts
  'GET:/api/v1/inventory/alerts': {
    status: 200,
    body: {
      data: [
        {
          id: 'alert-001', partId: 'part-002', partNumber: 'BRK-1040',
          partDescription: 'Brake Pad Set — Front', locationId: 'loc-wh-001',
          locationName: 'Main Warehouse', quantityOnHand: 8, reorderPoint: 10,
          stockStatus: 'LOW', raisedAt: '2026-08-10T14:30:00Z',
          asOf: '2026-08-11T10:00:00Z',
        },
        {
          id: 'alert-002', partId: 'part-003', partNumber: 'HVA-0055',
          partDescription: 'HVAC Refrigerant R-410A (Can)', locationId: 'loc-van-001',
          locationName: 'Van 12 — J. Smith', quantityOnHand: 0, reorderPoint: 2,
          stockStatus: 'OUT', raisedAt: '2026-08-09T08:00:00Z',
          asOf: '2026-08-11T10:00:00Z',
        },
      ],
      page: { number: 0, size: 50, totalElements: 2, totalPages: 1, estimated: false },
      _links: {},
      asOf: '2026-08-11T10:00:00Z',
    },
    headers: { 'ETag': '"alerts-etag-v1"' },
  },

  // Inventory movements
  'GET:/api/v1/inventory/movements': {
    status: 200,
    body: {
      data: [
        {
          id: 'mv-001', partId: 'part-002', partNumber: 'BRK-1040',
          locationId: 'loc-wh-001', movementType: 'CONSUMPTION', quantity: 4,
          reasonCode: 'CONSUMED_ON_JOB', workOrderId: 'wo-001',
          occurredAt: '2026-08-11T09:15:00Z', performedBy: 'J. Smith',
        },
        {
          id: 'mv-002', partId: 'part-002', partNumber: 'BRK-1040',
          locationId: 'loc-wh-001', movementType: 'RECEIPT', quantity: 20,
          reasonCode: 'PO_RECEIPT', workOrderId: null,
          occurredAt: '2026-08-10T08:00:00Z', performedBy: 'System',
        },
      ],
      page: { number: 0, size: 50, totalElements: 2, totalPages: 1, estimated: false },
      _links: {},
    },
    headers: { 'ETag': '"movements-etag-v1"' },
  },

  // Parts search
  'GET:/api/v1/inventory/parts/search': {
    status: 200,
    body: {
      data: [
        { id: 'part-001', partNumber: 'FLT-2890', description: 'Oil Filter — Heavy Duty', unitOfMeasure: 'EA', availableQuantity: 45 },
        { id: 'part-002', partNumber: 'BRK-1040', description: 'Brake Pad Set — Front', unitOfMeasure: 'SET', availableQuantity: 8 },
      ],
    },
  },

  // Parts consumption
  'POST:/api/v1/inventory/consumptions': {
    status: 201,
    body: { consumptionId: 'cons-001', applied: true },
  },

  // Parts returns
  'POST:/api/v1/inventory/returns': {
    status: 201,
    body: { returnId: 'ret-001', applied: true },
  },

  // User preferences
  'GET:/api/v1/users/me/preferences': {
    status: 200,
    body: { userId: 'user-001', storedPreference: 'SYSTEM', effectivePreference: 'LIGHT' },
  },

  // Admin — Customers
  'GET:/api/v1/customers': {
    status: 200,
    body: {
      data: [
        { id: 'cust-001', name: 'Acme Corp', contactEmail: 'ops@acmecorp.example', contactPhone: '+44 20 1234 5678', address: '1 Acme Way', active: true, createdAt: '2026-01-10T09:00:00Z' },
        { id: 'cust-002', name: 'Beta Industries', contactEmail: 'facilities@beta.example', contactPhone: null, address: '22 Beta St', active: true, createdAt: '2026-02-15T11:30:00Z' },
      ],
      page: { number: 0, size: 20, totalElements: 2, totalPages: 1, estimated: false },
      _links: { self: '/api/v1/customers?page=0&size=20', next: null, prev: null },
    },
  },
  'POST:/api/v1/customers': {
    status: 201,
    body: { id: 'cust-new', name: 'New Customer', contactEmail: null, contactPhone: null, address: null, active: true, createdAt: '2026-08-12T10:00:00Z' },
  },
  'PUT:/api/v1/customers/cust-001': {
    status: 200,
    body: { id: 'cust-001', name: 'Acme Corp Updated', contactEmail: 'ops@acmecorp.example', contactPhone: '+44 20 1234 5678', address: '1 Acme Way', active: true, createdAt: '2026-01-10T09:00:00Z' },
  },
  'DELETE:/api/v1/customers/cust-001': { status: 200, body: { id: 'cust-001', active: false } },

  // Admin — Sites
  'GET:/api/v1/sites': {
    status: 200,
    body: {
      data: [
        { id: 'site-001', customerId: 'cust-001', customerName: 'Acme Corp', name: 'London HQ', address: '1 Acme Way', active: true, createdAt: '2026-01-10T09:00:00Z' },
      ],
      page: { number: 0, size: 20, totalElements: 1, totalPages: 1, estimated: false },
      _links: { self: '/api/v1/sites?page=0&size=20', next: null, prev: null },
    },
  },
  'POST:/api/v1/sites': {
    status: 201,
    body: { id: 'site-new', customerId: 'cust-001', customerName: 'Acme Corp', name: 'New Site', address: null, active: true, createdAt: '2026-08-12T10:00:00Z' },
  },

  // Admin — Assets
  'GET:/api/v1/assets': {
    status: 200,
    body: {
      data: [
        { id: 'asset-001', siteId: 'site-001', siteName: 'London HQ', assetType: 'HVAC_UNIT', serialNumber: 'HV-0001', model: 'Carrier 30XW', active: true, createdAt: '2026-01-10T09:00:00Z' },
      ],
      page: { number: 0, size: 20, totalElements: 1, totalPages: 1, estimated: false },
      _links: { self: '/api/v1/assets?page=0&size=20', next: null, prev: null },
    },
  },
  'POST:/api/v1/assets': {
    status: 201,
    body: { id: 'asset-new', siteId: 'site-001', siteName: 'London HQ', assetType: 'BOILER', serialNumber: null, model: null, active: true, createdAt: '2026-08-12T10:00:00Z' },
  },

  // Admin — Technicians
  'GET:/api/v1/technicians': {
    status: 200,
    body: {
      data: [
        { id: 'tech-001', userId: 'user-002', displayName: 'John Smith', email: 'j.smith@example.com', active: true, createdAt: '2026-01-10T09:00:00Z' },
        { id: 'tech-002', userId: 'user-003', displayName: 'Jane Doe',   email: 'j.doe@example.com',   active: true, createdAt: '2026-02-01T09:00:00Z' },
      ],
      page: { number: 0, size: 20, totalElements: 2, totalPages: 1, estimated: false },
      _links: { self: '/api/v1/technicians?page=0&size=20', next: null, prev: null },
    },
  },

  // Admin — Certification types
  'GET:/api/v1/certification-types': {
    status: 200,
    body: {
      data: [
        { id: '00000000-0000-7039-8000-000000000001', code: 'GAS_SAFE',          displayName: 'Gas Safe Registration',    regulated: true,  defaultValidityMonths: 12, active: true },
        { id: '00000000-0000-7039-8000-000000000002', code: 'REFRIGERANT_F_GAS', displayName: 'F-Gas Refrigerant Handling', regulated: true,  defaultValidityMonths: 24, active: true },
        { id: '00000000-0000-7039-8000-000000000004', code: 'FIRST_AID_BASIC',   displayName: 'Basic First Aid',          regulated: false, defaultValidityMonths: 36, active: true },
      ],
      page: { number: 0, size: 25, totalElements: 3, totalPages: 1, estimated: false },
      _links: { self: '/api/v1/certification-types?page=0&size=25', next: null, prev: null },
    },
  },
  'POST:/api/v1/certification-types': {
    status: 201,
    body: { id: 'ct-new', code: 'NEW_TYPE', displayName: 'New Type', regulated: false, defaultValidityMonths: null, active: true },
  },

  // Admin — Technician certifications
  'GET:/api/v1/technicians/tech-001/certifications': {
    status: 200,
    body: {
      data: [
        { id: 'tc-001', typeCode: 'GAS_SAFE', typeDisplayName: 'Gas Safe Registration', regulated: true,  certificateReference: 'GS-2024-00123', issuedOn: '2025-01-15', expiresOn: '2027-12-31', current: true,  daysUntilExpiry: 506 },
        { id: 'tc-002', typeCode: 'FIRST_AID_BASIC', typeDisplayName: 'Basic First Aid', regulated: false, certificateReference: null,            issuedOn: '2023-06-01', expiresOn: '2026-09-01', current: true,  daysUntilExpiry: 20  },
        { id: 'tc-003', typeCode: 'WORKING_AT_HEIGHT', typeDisplayName: 'Working at Height', regulated: false, certificateReference: 'WAH-2023-00055', issuedOn: '2023-07-01', expiresOn: '2025-07-01', current: false, daysUntilExpiry: null },
      ],
      page: { number: 0, size: 20, totalElements: 3, totalPages: 1, estimated: false },
      _links: { self: '/api/v1/technicians/tech-001/certifications?page=0&size=20', next: null, prev: null },
    },
  },
  'PUT:/api/v1/technicians/tech-001/certifications': {
    status: 200,
    body: [
      { typeCode: 'GAS_SAFE',        outcome: 'COMMITTED', error: null },
      { typeCode: 'FIRST_AID_BASIC', outcome: 'COMMITTED', error: null },
    ],
  },

  // Work order creation
  'POST:/api/v1/work-orders': {
    status: 201,
    body: {
      id: 'wo-new-001', reference: 'WO-2026-100', state: 'NEW', priority: 'HIGH',
      customerId: 'cust-001', customerName: 'Acme Corp',
      siteId: 'site-001', siteName: 'London HQ', assetId: null,
      faultDescription: 'HVAC unit fault.', atRisk: false,
      responseDeadline:   new Date(Date.now() + 4 * 60 * 60 * 1000).toISOString(),
      resolutionDeadline: new Date(Date.now() + 8 * 60 * 60 * 1000).toISOString(),
      legalNextEvents: ['ASSIGN', 'CANCEL'], appliedSlaPolicyId: 'sla-001', version: 1,
      createdAt: new Date().toISOString(),
    },
  },

  // SLA policy by priority
  'GET:/api/v1/sla-policies/by-priority/URGENT': {
    status: 200,
    body: { priority: 'URGENT', responseMinutes: 60,   resolutionMinutes: 240,  atRiskFraction: 0.80 },
  },
  'GET:/api/v1/sla-policies/by-priority/HIGH': {
    status: 200,
    body: { priority: 'HIGH',   responseMinutes: 240,  resolutionMinutes: 480,  atRiskFraction: 0.80 },
  },
  'GET:/api/v1/sla-policies/by-priority/NORMAL': {
    status: 200,
    body: { priority: 'NORMAL', responseMinutes: 480,  resolutionMinutes: 1440, atRiskFraction: 0.80 },
  },
  'GET:/api/v1/sla-policies/by-priority/LOW': {
    status: 200,
    body: { priority: 'LOW',    responseMinutes: 1440, resolutionMinutes: 4320, atRiskFraction: 0.80 },
  },

  // Privacy — Classification registry
  'GET:/api/v1/privacy/classifications': {
    status: 200,
    body: {
      data: [
        { id: 'cls-001', module: 'identity', entityName: 'AppUser', fieldName: 'email',       tier: 'CONFIDENTIAL', lawfulBasisNote: 'Legitimate interest', handlingNotes: 'Email for account comms only', updatedAt: '2026-01-01T00:00:00Z', version: 1 },
        { id: 'cls-002', module: 'identity', entityName: 'AppUser', fieldName: 'phoneNumber', tier: 'CONFIDENTIAL', lawfulBasisNote: null, handlingNotes: null, updatedAt: '2026-01-01T00:00:00Z', version: 1 },
        { id: 'cls-003', module: 'identity', entityName: 'AppUser', fieldName: null,          tier: 'INTERNAL',     lawfulBasisNote: null, handlingNotes: 'Internal user record', updatedAt: '2026-01-01T00:00:00Z', version: 1 },
        { id: 'cls-004', module: 'workforce', entityName: 'Technician', fieldName: 'name',   tier: 'CONFIDENTIAL', lawfulBasisNote: 'Contract', handlingNotes: null, updatedAt: '2026-02-01T00:00:00Z', version: 2 },
      ],
      page: { number: 0, size: 20, totalElements: 4, totalPages: 1, estimated: false },
      _links: { self: '/api/v1/privacy/classifications?page=0&size=20', next: null, prev: null },
    },
  },
  'PUT:/api/v1/privacy/classifications/cls-001': {
    status: 200,
    body: { id: 'cls-001', module: 'identity', entityName: 'AppUser', fieldName: 'email', tier: 'RESTRICTED', lawfulBasisNote: 'Legitimate interest', handlingNotes: 'Updated notes', updatedAt: '2026-08-12T10:00:00Z', version: 2 },
  },

  // Privacy — Retention policies
  'GET:/api/v1/privacy/retention-policies': {
    status: 200,
    body: {
      data: [
        { id: 'ret-001', dataCategory: 'SUBJECT_ERASURE_TOMBSTONE', entityName: 'subject_erasure', periodValue: 7, periodUnit: 'YEARS', anchorField: 'erased_at', disposalMethod: 'CRYPTO_ERASE', legalHold: false, ratified: true, enabled: true, notes: 'DPO evidence', version: 1 },
        { id: 'ret-002', dataCategory: 'APP_USER', entityName: 'AppUser', periodValue: 6, periodUnit: 'YEARS', anchorField: 'closedAt', disposalMethod: 'CRYPTO_ERASE', legalHold: false, ratified: false, enabled: false, notes: 'Pending DPO ratification', version: 0 },
        { id: 'ret-003', dataCategory: 'WORK_ORDER', entityName: 'WorkOrder', periodValue: 7, periodUnit: 'YEARS', anchorField: 'closedAt', disposalMethod: 'PHYSICAL_DELETE', legalHold: true, ratified: true, enabled: true, notes: 'Legal hold active — pending litigation', version: 3 },
      ],
      page: { number: 0, size: 20, totalElements: 3, totalPages: 1, estimated: false },
      _links: { self: '/api/v1/privacy/retention-policies?page=0&size=20', next: null, prev: null },
    },
  },
  'PUT:/api/v1/privacy/retention-policies/ret-001': {
    status: 200,
    body: { id: 'ret-001', dataCategory: 'SUBJECT_ERASURE_TOMBSTONE', entityName: 'subject_erasure', periodValue: 10, periodUnit: 'YEARS', anchorField: 'erased_at', disposalMethod: 'CRYPTO_ERASE', legalHold: false, ratified: true, enabled: true, notes: 'Updated', version: 2 },
  },
  'POST:/api/v1/privacy/retention-policies/ret-001/dry-run': {
    status: 200,
    body: { eligibleCount: 0, cutoffAt: '2019-08-12T00:00:00Z', oldestEligibleAt: null },
  },
  'POST:/api/v1/privacy/retention-policies/ret-002/dry-run': {
    status: 200,
    body: { eligibleCount: 142, cutoffAt: '2020-08-12T00:00:00Z', oldestEligibleAt: '2017-03-15T09:22:00Z' },
  },

  // Privacy — DSAR queue
  'GET:/api/v1/privacy/dsar-requests': {
    status: 200,
    body: {
      data: [
        { id: 'dsar-001', requestType: 'ACCESS',        subjectType: 'APP_USER', subjectId: 'user-101', state: 'VERIFIED',     submittedAt: '2026-07-15T08:00:00Z', dueAt: '2026-08-14T08:00:00Z', remainingDays: 2,  atRisk: true,  outcome: null, version: 2 },
        { id: 'dsar-002', requestType: 'ERASURE',       subjectType: 'APP_USER', subjectId: 'user-102', state: 'RECEIVED',     submittedAt: '2026-08-01T10:00:00Z', dueAt: '2026-08-31T10:00:00Z', remainingDays: 19, atRisk: false, outcome: null, version: 1 },
        { id: 'dsar-003', requestType: 'RECTIFICATION', subjectType: 'APP_USER', subjectId: 'user-103', state: 'FULFILLED',    submittedAt: '2026-06-01T09:00:00Z', dueAt: '2026-07-01T09:00:00Z', remainingDays: 0,  atRisk: false, outcome: 'FULFILLED', version: 3 },
        { id: 'dsar-004', requestType: 'ACCESS',        subjectType: 'TECHNICIAN', subjectId: 'tech-201', state: 'REJECTED',   submittedAt: '2026-05-01T09:00:00Z', dueAt: '2026-05-31T09:00:00Z', remainingDays: -5, atRisk: false, outcome: 'REJECTED', version: 2 },
      ],
      page: { number: 0, size: 20, totalElements: 4, totalPages: 1, estimated: false },
      _links: { self: '/api/v1/privacy/dsar-requests?page=0&size=20', next: null, prev: null },
    },
  },
  'GET:/api/v1/privacy/dsar-requests/dsar-001': {
    status: 200,
    body: {
      id: 'dsar-001', requestType: 'ACCESS', subjectType: 'APP_USER', subjectId: 'user-101',
      state: 'VERIFIED', submittedAt: '2026-07-15T08:00:00Z', dueAt: '2026-08-14T08:00:00Z',
      remainingDays: 2, atRisk: true, identityVerifiedAt: '2026-07-16T10:00:00Z',
      verificationMethod: 'GOVERNMENT_ID', assignedHandler: null, outcome: null, version: 2,
      manifest: null,
    },
  },
  'GET:/api/v1/privacy/dsar-requests/dsar-002': {
    status: 200,
    body: {
      id: 'dsar-002', requestType: 'ERASURE', subjectType: 'APP_USER', subjectId: 'user-102',
      state: 'RECEIVED', submittedAt: '2026-08-01T10:00:00Z', dueAt: '2026-08-31T10:00:00Z',
      remainingDays: 19, atRisk: false, identityVerifiedAt: null,
      verificationMethod: null, assignedHandler: null, outcome: null, version: 1,
      manifest: null,
    },
  },
  'GET:/api/v1/privacy/dsar-requests/dsar-003': {
    status: 200,
    body: {
      id: 'dsar-003', requestType: 'RECTIFICATION', subjectType: 'APP_USER', subjectId: 'user-103',
      state: 'FULFILLED', submittedAt: '2026-06-01T09:00:00Z', dueAt: '2026-07-01T09:00:00Z',
      remainingDays: 0, atRisk: false, identityVerifiedAt: '2026-06-02T11:00:00Z',
      verificationMethod: 'PHONE_VERIFICATION', assignedHandler: null, outcome: 'FULFILLED', version: 3,
      manifest: [
        { sectionName: 'identity.app_user', sourceModule: 'identity', rowCount: 1 },
        { sectionName: 'workorder.history', sourceModule: 'workorder', rowCount: 17 },
      ],
    },
  },
  'GET:/api/v1/privacy/dsar-requests/dsar-003/export': {
    status: 200,
    body: { downloadUrl: 'https://storage.example/exports/dsar-003-signed?token=abc123', expiresIn: 300 },
  },

  // Privacy — Erasure initiation
  'POST:/api/v1/privacy/subjects/APP_USER/user-101/erasure': {
    status: 202,
    body: { erasureId: 'era-001', state: 'PENDING' },
  },

  // Privacy — Erasure tombstone
  'GET:/api/v1/privacy/erasures/era-001': {
    status: 200,
    body: {
      id: 'era-001', dsarRequestId: 'dsar-001', subjectType: 'APP_USER', subjectId: 'user-101',
      keyReference: 'APP_USER/user-101/v1', erasedAt: '2026-08-12T10:00:00Z',
      actor: 'SYSTEM', erasedSections: [{ name: 'identity.app_user', rowCount: 1 }],
      verificationResults: [{ scope: 'live-tables', plaintextFound: false, itemsChecked: 5, checkedAt: '2026-08-12T10:01:00Z' }],
      outcome: 'COMPLETED', refusalReason: null,
    },
  },

  // ── Technician: day list ─────────────────────────────────────────────────

  // Technician day list — 200 OK (happy path used by both unit and Playwright tests)
  'GET:/api/v1/technicians/me/work-orders': {
    status: 200,
    body: {
      data: [
        {
          id: 'wo-tech-001', reference: 'WO-0042', state: 'ASSIGNED', priority: 'HIGH',
          customerName: 'Acme Corp', siteName: 'Main Campus', siteAddress: '1 Main St, London',
          description: 'Replace faulty circuit breaker in panel B',
          scheduledAt: '2026-08-12T09:00:00Z',
          responseDeadline: '2026-08-12T13:00:00Z', resolutionDeadline: '2026-08-12T17:00:00Z',
          atRisk: false, version: 1,
        },
        {
          id: 'wo-tech-002', reference: 'WO-0043', state: 'IN_PROGRESS', priority: 'URGENT',
          customerName: 'Beta Industries', siteName: 'Southside Warehouse',
          siteAddress: '88 Industrial Way, Manchester',
          description: 'HVAC compressor failure — cooling plant room',
          scheduledAt: '2026-08-12T11:30:00Z',
          responseDeadline: '2026-08-12T12:00:00Z', resolutionDeadline: '2026-08-12T14:00:00Z',
          atRisk: true, version: 3,
        },
      ],
      page: { number: 0, size: 20, totalElements: 2, totalPages: 1, estimated: false },
      _links: { self: '/api/v1/technicians/me/work-orders?page=0&size=20', next: null, prev: null },
      asOf: '2026-08-12T08:00:00Z',
    },
    headers: { 'ETag': '"tech-day-etag-v1"' },
  },

  // Technician update status — mutation (always 200 for the happy path)
  'PATCH:/api/v1/technicians/me/work-orders/wo-tech-001/status': {
    status: 200,
    body: { id: 'wo-tech-001', state: 'IN_PROGRESS', version: 2 },
  },

  // ── Portal: service history ──────────────────────────────────────────────

  // Service history — page 0 (happy path, 8 total records across 2 pages)
  'GET:/api/v1/portal/service-history': {
    status: 200,
    body: {
      data: [
        { id: 'srh-001', reference: 'WO-P100', siteName: 'Northgate Office',  statusLabel: 'Completed', statusGroup: 'closed', priority: 'High',   submittedAt: '2026-07-10T09:00:00Z', resolvedAt: '2026-07-10T14:30:00Z', closedAt: '2026-07-11T09:00:00Z', surveyEligible: true,  version: 5 },
        { id: 'srh-002', reference: 'WO-P101', siteName: 'Southside Warehouse', statusLabel: 'In progress', statusGroup: 'open', priority: 'Urgent', submittedAt: '2026-07-12T11:00:00Z', resolvedAt: null, closedAt: null, surveyEligible: false, version: 2 },
        { id: 'srh-003', reference: 'WO-P102', siteName: 'Northgate Office',  statusLabel: 'Scheduled',   statusGroup: 'open', priority: 'Normal', submittedAt: '2026-07-14T08:00:00Z', resolvedAt: null, closedAt: null, surveyEligible: false, version: 1 },
        { id: 'srh-004', reference: 'WO-P103', siteName: 'Southside Warehouse', statusLabel: 'Completed', statusGroup: 'closed', priority: 'Normal', submittedAt: '2026-07-01T10:00:00Z', resolvedAt: '2026-07-02T13:00:00Z', closedAt: '2026-07-03T10:00:00Z', surveyEligible: false, version: 6 },
        { id: 'srh-005', reference: 'WO-P104', siteName: 'Northgate Office',  statusLabel: 'Completed', statusGroup: 'closed', priority: 'High',   submittedAt: '2026-06-28T09:30:00Z', resolvedAt: '2026-06-28T17:00:00Z', closedAt: '2026-06-29T09:00:00Z', surveyEligible: false, version: 4 },
      ],
      page: { number: 0, size: 5, totalElements: 8, totalPages: 2, estimated: false },
      _links: { self: '/api/v1/portal/service-history?page=0&size=5', next: '/api/v1/portal/service-history?page=1&size=5', prev: null },
    },
    headers: { 'ETag': '"history-etag-p0-v1"' },
  },

  // Survey — answerable (srh-001)
  'GET:/api/v1/portal/service-requests/srh-001/survey': {
    status: 200,
    body: { workOrderId: 'srh-001', reference: 'WO-P100', windowExpiresAt: '2026-09-15T23:59:00Z', alreadyAnswered: false, outcome: null },
  },

  // Survey submit — 201 Created
  'POST:/api/v1/portal/service-requests/srh-001/survey': {
    status: 201,
    body: { workOrderId: 'srh-001', score: 4, nps: null, submittedAt: '2026-08-12T10:00:00Z' },
  },

  // Survey — already answered (srh-004)
  'GET:/api/v1/portal/service-requests/srh-004/survey': {
    status: 200,
    body: { workOrderId: 'srh-004', reference: 'WO-P103', windowExpiresAt: '2026-07-10T23:59:00Z', alreadyAnswered: true, outcome: { score: 4, nps: 8, comment: null, submittedAt: '2026-07-04T10:15:00Z' } },
  },

  // Survey — expired window (srh-005)
  'GET:/api/v1/portal/service-requests/srh-005/survey': {
    status: 200,
    body: { workOrderId: 'srh-005', reference: 'WO-P104', windowExpiresAt: '2026-07-06T23:59:00Z', alreadyAnswered: false, outcome: null },
  },

  // ── Portal: customer self-service ────────────────────────────────────────

  // Customer's sites list
  'GET:/api/v1/portal/sites': {
    status: 200,
    body: {
      data: [
        { id: 'site-p01', name: 'Northgate Office', address: '12 Northgate Rd, London, EC1A 1BB' },
        { id: 'site-p02', name: 'Southside Warehouse', address: '88 Industrial Way, Manchester, M1 5AB' },
      ],
    },
    headers: { 'ETag': '"portal-sites-etag-v1"' },
  },

  // Site assets
  'GET:/api/v1/portal/sites/site-p01/assets': {
    status: 200,
    body: {
      data: [
        { id: 'asset-pa01', assetTag: 'HVAC-101', model: 'Daikin VRV', assetType: 'HVAC Unit' },
        { id: 'asset-pa02', assetTag: 'LIFT-02', model: 'Otis 3000', assetType: 'Lift' },
      ],
    },
  },
  'GET:/api/v1/portal/sites/site-p02/assets': {
    status: 200,
    body: { data: [] },
  },

  // Submit new service request — 201 Created
  'POST:/api/v1/portal/service-requests': {
    status: 201,
    body: {
      workOrderId: 'wo-portal-001',
      reference: 'WO-P001',
      respondByAt: '2026-08-13T12:00:00Z',
      resolveByAt: '2026-08-14T17:00:00Z',
    },
  },

  // Service request status — default happy path
  'GET:/api/v1/portal/service-requests/wo-portal-001/status': {
    status: 200,
    body: {
      reference: 'WO-P001',
      currentStatusLabel: 'Request received — under review',
      siteName: 'Northgate Office',
      respondByAt: '2026-08-13T12:00:00Z',
      resolveByAt: '2026-08-14T17:00:00Z',
      milestones: [
        { at: '2026-08-12T09:00:00Z', label: 'Request submitted' },
        { at: '2026-08-12T09:05:00Z', label: 'Request received — under review' },
      ],
      customerNote: 'A technician will contact you to arrange access.',
      observedAt: '2026-08-12T09:10:00Z',
    },
    headers: { 'ETag': '"portal-status-v1"' },
  },
};

// ---- Status fixture helpers --------------------------------------------

/** Returns a 4xx/5xx error envelope for a given status. */
export function errorFixture(status, code, message, fieldErrors = []) {
  return {
    status,
    body: {
      status,
      code: code ?? _codeForStatus(status),
      message: message ?? _msgForStatus(status),
      fieldErrors,
      traceId: 'test-trace-id',
    },
  };
}

function _codeForStatus(status) {
  const m = { 400:'BAD_REQUEST',401:'UNAUTHENTICATED',403:'FORBIDDEN',404:'NOT_FOUND',409:'CONFLICT',422:'UNPROCESSABLE_ENTITY',429:'RATE_LIMITED',503:'SERVICE_UNAVAILABLE' };
  return m[status] ?? 'UNEXPECTED_ERROR';
}
function _msgForStatus(status) {
  const m = { 400:'Bad request.',401:'Unauthenticated.',403:'Forbidden.',404:'Not found.',409:'Conflict.',422:'Unprocessable.',429:'Rate limited.',503:'Unavailable.' };
  return m[status] ?? 'Error.';
}

// ---- Auth-specific fixture helpers ------------------------------------

/**
 * Returns a contract-accurate 401 INVALID_CREDENTIALS fixture for the login
 * endpoint (POST /api/v1/auth/login).  The message is intentionally generic
 * and does not disclose whether the account exists (BR-12).
 * @returns {{ status: number, body: unknown }}
 */
export function loginUnauthorizedFixture() {
  return {
    status: 401,
    body: {
      status: 401,
      code: 'INVALID_CREDENTIALS',
      message: 'Incorrect email or password.',
      fieldErrors: [],
      traceId: 'test-trace-id',
    },
  };
}

/**
 * Returns a contract-accurate 400 VALIDATION_FAILED fixture with field errors.
 * @param {Array<{ field: string, message: string }>} [fieldErrors]
 * @returns {{ status: number, body: unknown }}
 */
export function loginBadRequestFixture(fieldErrors = [
  { field: 'email', message: 'must be a valid email address' },
]) {
  return {
    status: 400,
    body: {
      status: 400,
      code: 'VALIDATION_FAILED',
      message: 'The request contained invalid data.',
      fieldErrors,
      traceId: 'test-trace-id',
    },
  };
}

/**
 * Returns a contract-accurate 429 RATE_LIMITED fixture with an optional
 * Retry-After header value in seconds.
 * @param {number} [retryAfterSeconds]
 * @returns {{ status: number, body: unknown, headers?: Record<string, string> }}
 */
export function loginRateLimitedFixture(retryAfterSeconds = 60) {
  return {
    status: 429,
    body: {
      status: 429,
      code: 'RATE_LIMITED',
      message: 'Too many sign-in attempts. Please wait before trying again.',
      fieldErrors: [],
      traceId: 'test-trace-id',
    },
    headers: { 'Retry-After': String(retryAfterSeconds) },
  };
}

/**
 * Returns a contract-accurate 503 SERVICE_UNAVAILABLE fixture for the login
 * endpoint, e.g. when the auth backend is unreachable.
 * @returns {{ status: number, body: unknown }}
 */
export function loginServiceUnavailableFixture() {
  return {
    status: 503,
    body: {
      status: 503,
      code: 'SERVICE_UNAVAILABLE',
      message: 'The authentication service is temporarily unavailable. Please try again shortly.',
      fieldErrors: [],
      traceId: 'test-trace-id',
    },
  };
}

/**
 * Returns a 401 REAUTHENTICATION_REQUIRED fixture for the refresh endpoint,
 * indicating the refresh cookie has expired or was revoked.
 * @returns {{ status: number, body: unknown }}
 */
export function refreshExpiredFixture() {
  return {
    status: 401,
    body: {
      status: 401,
      code: 'REAUTHENTICATION_REQUIRED',
      message: 'Reauthentication required.',
      fieldErrors: [],
      traceId: 'test-trace-id',
    },
  };
}

/** The mock access token used in success responses. */
export { MOCK_ACCESS_TOKEN, MOCK_REFRESHED_TOKEN };

// ---- Multi-page paginated fixture --------------------------------------

/** Returns a two-page fixture for path, page 0 and page 1. */
export function twoPageFixture(path) {
  const page0 = {
    data: Array.from({ length: 20 }, (_, i) => ({ id: `item-${i}` })),
    page: { number: 0, size: 20, totalElements: 25, totalPages: 2, estimated: false },
    _links: { self: `${path}?page=0&size=20`, next: `${path}?page=1&size=20`, prev: null },
  };
  const page1 = {
    data: Array.from({ length: 5 }, (_, i) => ({ id: `item-${20 + i}` })),
    page: { number: 1, size: 20, totalElements: 25, totalPages: 2, estimated: false },
    _links: { self: `${path}?page=1&size=20`, next: null, prev: `${path}?page=0&size=20` },
  };
  return { page0, page1 };
}

// ---- Mock fetch implementation -----------------------------------------

async function mockFetch(input, init = {}) {
  const url    = typeof input === 'string' ? input : input.url;
  const method = (init.method ?? 'GET').toUpperCase();
  const path   = url.split('?')[0];
  const key    = `${method}:${path}`;

  // Per-test override takes priority
  const override = _overrides.get(key);
  _overrides.delete(key); // one-shot

  const route = override ?? DEFAULT_ROUTES[key];

  // ETag conditional GET
  if (method === 'GET') {
    const storedEtag = _etags.get(path);
    const inm = (init.headers && (init.headers['If-None-Match'] || new Headers(init.headers).get('If-None-Match')));
    if (storedEtag && inm && inm === storedEtag) {
      return _makeResponse(304, null, {});
    }
  }

  if (!route) {
    return _makeResponse(404, { code: 'NOT_FOUND', message: `No mock for ${key}`, traceId: 'test' }, {});
  }

  return _makeResponse(route.status, route.body, route.headers ?? {});
}

function _makeResponse(status, body, headers = {}) {
  const responseHeaders = new Headers({
    'Content-Type': 'application/json',
    ...headers,
  });

  return new Response(
    body !== null ? JSON.stringify(body) : null,
    { status, headers: responseHeaders },
  );
}
