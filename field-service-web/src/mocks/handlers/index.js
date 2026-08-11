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

const DEFAULT_ROUTES = {
  // Auth
  'POST:/api/v1/auth/login': {
    status: 200,
    body: { accessToken: 'mock-access-token', expiresIn: 900 },
    headers: { 'Set-Cookie': 'refresh_token=mock-refresh; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth' },
  },
  'POST:/api/v1/auth/refresh': {
    status: 200,
    body: { accessToken: 'mock-refreshed-token', expiresIn: 900 },
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

  // Inventory stock
  'GET:/api/v1/inventory/stock': {
    status: 200,
    body: { data: [], page: { number: 0, size: 20, totalElements: 0, totalPages: 0, estimated: false }, _links: {} },
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
