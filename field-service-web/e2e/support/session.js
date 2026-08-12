/**
 * @fileoverview Programmatic technician session factory for E2E tests.
 *
 * Obtains a technician session by intercepting the auth login endpoint via
 * Playwright page.route() and returning a synthetic JWT. The access token
 * stays in JavaScript memory only — never written to localStorage or cookies
 * (security constraint).
 *
 * Usage:
 *   const session = await createTechnicianSession(page, TECH_1)
 *   // page is now authenticated as TECH_1
 */
import { TECH_1 } from './fixtures.js'

const BASE = '/api/v1'
const LOGIN_PATH = `${BASE}/auth/login`
const REFRESH_PATH = `${BASE}/auth/refresh`
const LOGOUT_PATH = `${BASE}/auth/logout`
const DAY_LIST_PATH = `${BASE}/technicians/me/work-orders`

// Synthetic JWT payload (not a real signed token — used for in-memory auth only)
// Parts: header.payload.signature — all base64url-encoded
function buildSyntheticJwt(userId, technicianId) {
  const header = btoa(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '')
  const now = Math.floor(Date.now() / 1000)
  const payload = btoa(JSON.stringify({
    sub: userId,
    roles: ['TECHNICIAN'],
    technicianId,
    iss: 'http://localhost:8080',
    aud: 'field-service-api',
    iat: now,
    exp: now + 900,
    jti: crypto.randomUUID(),
  })).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '')
  const sig = 'test-sig'
  return `${header}.${payload}.${sig}`
}

/**
 * Creates a technician session by:
 * 1. Intercepting POST /api/v1/auth/login → returns synthetic JWT
 * 2. Intercepting POST /api/v1/auth/logout → returns 204
 * 3. Navigating to /technician/jobs to trigger auth
 *
 * @param {import('@playwright/test').Page} page
 * @param {object} tech — identity fixture, defaults to TECH_1
 * @returns {Promise<{accessToken: string, userId: string, technicianId: string}>}
 */
export async function createTechnicianSession(page, tech = TECH_1) {
  const accessToken = buildSyntheticJwt(tech.userId, tech.technicianId)

  // Intercept login endpoint
  await page.route(`**${LOGIN_PATH}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ accessToken }),
    })
  })

  // Intercept logout
  await page.route(`**${LOGOUT_PATH}`, async (route) => {
    await route.fulfill({ status: 204, body: '' })
  })

  // Intercept refresh (keeps the in-memory token alive)
  await page.route(`**${REFRESH_PATH}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ accessToken }),
    })
  })

  return { accessToken, userId: tech.userId, technicianId: tech.technicianId }
}

/**
 * Intercepts the day-list endpoint and returns the given fixture data.
 * Call before navigating to /technician to ensure the first load succeeds.
 *
 * @param {import('@playwright/test').Page} page
 * @param {object} fixture — day-list JSON fixture
 */
export async function mockDayList(page, fixture) {
  await page.route(`**${DAY_LIST_PATH}**`, async (route) => {
    const ifNoneMatch = route.request().headers()['if-none-match']
    if (ifNoneMatch === '"e2e-day-list-v1"') {
      await route.fulfill({ status: 304, body: '' })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { etag: '"e2e-day-list-v1"' },
      body: JSON.stringify(fixture),
    })
  })
}

/**
 * Intercepts a work-order transitions endpoint.
 * Returns the given toState in the response.
 *
 * @param {import('@playwright/test').Page} page
 * @param {string} workOrderId
 * @param {string} fromState
 * @param {string} toState
 */
export async function mockTransition(page, workOrderId, fromState, toState) {
  await page.route(`**${BASE}/work-orders/${workOrderId}/transitions`, async (route) => {
    if (route.request().method() !== 'POST') { await route.continue(); return }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        workOrderId,
        fromState,
        toState,
        version: 1,
        legalEvents: nextEvents(toState),
        appliedAt: new Date().toISOString(),
      }),
    })
  })
}

/**
 * Intercepts a parts-consumption endpoint and returns success.
 */
export async function mockPartsConsumption(page, workOrderId) {
  await page.route(`**${BASE}/work-orders/${workOrderId}/parts`, async (route) => {
    if (route.request().method() !== 'POST') { await route.continue(); return }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ consumed: true, lines: [] }),
    })
  })
}

/**
 * Intercepts a parts-consumption endpoint and returns 422 (over-consumption).
 */
export async function mockPartsOverConsumption(page, workOrderId, partName, shortfall) {
  await page.route(`**${BASE}/work-orders/${workOrderId}/parts`, async (route) => {
    if (route.request().method() !== 'POST') { await route.continue(); return }
    await route.fulfill({
      status: 422,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'INSUFFICIENT_STOCK',
        message: `Insufficient stock for '${partName}': shortfall ${shortfall}.`,
        fieldErrors: [{ field: 'lines[0].quantity', message: `Shortfall: ${shortfall}` }],
        traceId: 'trace-e2e-422',
      }),
    })
  })
}

/**
 * Intercepts a transition to return 409 (illegal transition).
 */
export async function mockIllegalTransition(page, workOrderId) {
  await page.route(`**${BASE}/work-orders/${workOrderId}/transitions`, async (route) => {
    if (route.request().method() !== 'POST') { await route.continue(); return }
    await route.fulfill({
      status: 409,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'ILLEGAL_TRANSITION',
        message: 'This state transition is not permitted from the current state.',
        traceId: 'trace-e2e-409',
      }),
    })
  })
}

function nextEvents(state) {
  const map = {
    EN_ROUTE: ['START', 'HOLD', 'CANCEL'],
    IN_PROGRESS: ['COMPLETE', 'HOLD', 'CANCEL'],
    COMPLETED: ['CLOSE'],
    ON_HOLD: ['RESUME', 'CANCEL'],
  }
  return map[state] ?? []
}
