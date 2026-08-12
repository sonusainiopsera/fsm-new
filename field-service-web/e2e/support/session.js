/**
 * Programmatic auth session helpers for Playwright e2e tests.
 *
 * Signs in via POST /api/v1/auth/login and stores the access token so
 * specs can authenticate without driving the login form on every run.
 *
 * Usage:
 *   import { signInAsTechnician, applySessionStorage } from '../support/session.js';
 *   test.beforeEach(async ({ page }) => {
 *     await applySessionStorage(page, await signInAsTechnician(page));
 *   });
 */

export const TECHNICIAN_CREDENTIALS = {
  email: 'tech1@example.com',
  password: 'test-password',
};

export const TECHNICIAN_TWO_CREDENTIALS = {
  email: 'tech2@example.com',
  password: 'test-password',
};

/**
 * Signs in as Tech One (TECHNICIAN role) via the auth endpoint.
 * Returns the access token string.
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<string>} access token
 */
export async function signInAsTechnician(page) {
  const response = await page.request.post('/api/v1/auth/login', {
    data: TECHNICIAN_CREDENTIALS,
    headers: { 'Content-Type': 'application/json' },
  });

  if (!response.ok()) {
    const body = await response.text();
    throw new Error(`Login failed (${response.status()}): ${body}`);
  }

  const body = await response.json();
  if (!body.accessToken) {
    throw new Error(`Login response missing accessToken: ${JSON.stringify(body)}`);
  }
  return body.accessToken;
}

/**
 * Signs in as Tech Two (TECHNICIAN role) via the auth endpoint.
 * Returns the access token string.
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<string>} access token
 */
export async function signInAsTechnicianTwo(page) {
  const response = await page.request.post('/api/v1/auth/login', {
    data: TECHNICIAN_TWO_CREDENTIALS,
    headers: { 'Content-Type': 'application/json' },
  });

  if (!response.ok()) {
    throw new Error(`Tech Two login failed (${response.status()})`);
  }
  const body = await response.json();
  return body.accessToken;
}

/**
 * Stores the access token in the app's expected in-memory location
 * via a page init script. Must be called BEFORE page.goto().
 *
 * @param {import('@playwright/test').Page} page
 * @param {string} token
 */
export async function applySessionStorage(page, token) {
  await page.addInitScript((t) => {
    // Expose token for the app's auth module to pick up on initialisation
    window.__E2E_AUTH_TOKEN = t;
    try {
      sessionStorage.setItem('fsvc_access_token', t);
    } catch { /* ignore — some origins disallow sessionStorage */ }
  }, token);
}

/**
 * Reads the current idempotency key counter and increments it, returning a
 * stable key per spec invocation. Using a closure keeps keys unique across
 * parallel workers without wall-clock.
 */
let _idempotencySeq = 0;
export function nextIdempotencyKey(prefix = 'e2e') {
  return `${prefix}-${++_idempotencySeq}-${Date.now()}`;
}
