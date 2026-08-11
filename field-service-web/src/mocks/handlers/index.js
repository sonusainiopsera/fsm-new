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
