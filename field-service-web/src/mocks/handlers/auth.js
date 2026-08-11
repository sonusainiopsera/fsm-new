/**
 * @fileoverview MSW-style mock handlers for the auth API endpoints.
 *
 * These are plain fetch-intercept helpers used with mockTransport's fetch stub
 * pattern. They cover all status codes documented in the sign-in WO:
 *   - POST /api/v1/auth/login  → 200, 400, 401, 429, 503
 *   - POST /api/v1/auth/refresh → 200, 401
 *   - POST /api/v1/auth/logout  → 204
 *
 * Usage in tests:
 *   vi.stubGlobal('fetch', authHandlers.loginSuccess())
 *   vi.stubGlobal('fetch', authHandlers.loginFieldError())
 */

export const AUTH_LOGIN_URL = '/api/v1/auth/login'
export const AUTH_REFRESH_URL = '/api/v1/auth/refresh'
export const AUTH_LOGOUT_URL = '/api/v1/auth/logout'

/** A minimal valid JWT-shaped token for tests (not cryptographically valid). */
const FAKE_ACCESS_TOKEN =
  'eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.' +
  btoa(JSON.stringify({ sub: 'user-1', roles: ['TECHNICIAN'], exp: 9999999999 }))
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_') +
  '.fakesig'

/** Fixtures keyed by auth scenario. */
export const AUTH_FIXTURES = {
  loginSuccess: {
    status: 200,
    body: {
      accessToken: FAKE_ACCESS_TOKEN,
      roles: ['TECHNICIAN'],
      userId: 'user-1',
      storedPreference: null,
    },
  },
  loginSuccessManager: {
    status: 200,
    body: {
      accessToken: FAKE_ACCESS_TOKEN,
      roles: ['MANAGER'],
      userId: 'user-mgr',
      storedPreference: null,
    },
  },
  loginSuccessCustomer: {
    status: 200,
    body: {
      accessToken: FAKE_ACCESS_TOKEN,
      roles: ['CUSTOMER'],
      userId: 'user-cust',
      storedPreference: null,
    },
  },
  loginSuccessDispatch: {
    status: 200,
    body: {
      accessToken: FAKE_ACCESS_TOKEN,
      roles: ['DISPATCHER'],
      userId: 'user-disp',
      storedPreference: null,
    },
  },
  login400FieldErrors: {
    status: 400,
    body: {
      code: 'VALIDATION_FAILED',
      message: 'Request validation failed.',
      fieldErrors: [
        { field: 'email', message: 'must be a valid email address' },
        { field: 'password', message: 'must not be blank' },
      ],
      traceId: 'trace-400-auth',
    },
  },
  login401: {
    status: 401,
    body: {
      code: 'UNAUTHENTICATED',
      message: 'Invalid email or password.',
      fieldErrors: [],
      traceId: 'trace-401-auth',
    },
  },
  login429: {
    status: 429,
    headers: { 'Retry-After': '60' },
    body: {
      code: 'TOO_MANY_REQUESTS',
      message: 'Too many sign-in attempts.',
      fieldErrors: [],
      retryAfterSeconds: 60,
      traceId: 'trace-429-auth',
    },
  },
  login503: {
    status: 503,
    body: {
      code: 'SERVICE_UNAVAILABLE',
      message: 'The service is temporarily unavailable. Please try again shortly.',
      fieldErrors: [],
      traceId: 'trace-503-auth',
    },
  },
  refreshSuccess: {
    status: 200,
    body: {
      accessToken: FAKE_ACCESS_TOKEN,
      roles: ['TECHNICIAN'],
      userId: 'user-1',
    },
  },
  refresh401: {
    status: 401,
    body: {
      code: 'UNAUTHENTICATED',
      message: 'Refresh token expired or invalid.',
      fieldErrors: [],
      traceId: 'trace-refresh-401',
    },
  },
  logout204: {
    status: 204,
    body: null,
  },
}

/**
 * Build a fetch stub that returns the given fixture for the given URL.
 * All other URLs return 404.
 *
 * @param {string} url
 * @param {{ status: number, body: object|null, headers?: Record<string,string> }} fixture
 * @returns {(input: string, init?: RequestInit) => Promise<Response>}
 */
function makeFetchStub(url, fixture) {
  return async (input) => {
    if (input !== url) {
      return new Response(JSON.stringify({ code: 'NOT_FOUND' }), {
        status: 404,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    const responseHeaders = { 'Content-Type': 'application/json', ...(fixture.headers ?? {}) }
    const body = fixture.body !== null ? JSON.stringify(fixture.body) : ''
    return new Response(body || null, {
      status: fixture.status,
      headers: responseHeaders,
    })
  }
}

/**
 * A network-failure stub — rejects with TypeError (no connection).
 * @returns {() => Promise<never>}
 */
function makeNetworkErrorStub() {
  return async () => { throw new TypeError('Failed to fetch') }
}

/** Auth fetch stubs, keyed by scenario name. */
export const authHandlers = {
  loginSuccess: () => makeFetchStub(AUTH_LOGIN_URL, AUTH_FIXTURES.loginSuccess),
  loginSuccessManager: () => makeFetchStub(AUTH_LOGIN_URL, AUTH_FIXTURES.loginSuccessManager),
  loginSuccessCustomer: () => makeFetchStub(AUTH_LOGIN_URL, AUTH_FIXTURES.loginSuccessCustomer),
  loginSuccessDispatch: () => makeFetchStub(AUTH_LOGIN_URL, AUTH_FIXTURES.loginSuccessDispatch),
  login400: () => makeFetchStub(AUTH_LOGIN_URL, AUTH_FIXTURES.login400FieldErrors),
  login401: () => makeFetchStub(AUTH_LOGIN_URL, AUTH_FIXTURES.login401),
  login429: () => makeFetchStub(AUTH_LOGIN_URL, AUTH_FIXTURES.login429),
  login503: () => makeFetchStub(AUTH_LOGIN_URL, AUTH_FIXTURES.login503),
  loginNetworkError: makeNetworkErrorStub,
  refreshSuccess: () => makeFetchStub(AUTH_REFRESH_URL, AUTH_FIXTURES.refreshSuccess),
  refresh401: () => makeFetchStub(AUTH_REFRESH_URL, AUTH_FIXTURES.refresh401),
  logout204: () => makeFetchStub(AUTH_LOGOUT_URL, AUTH_FIXTURES.logout204),
}
