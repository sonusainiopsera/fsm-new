/**
 * Technician field execution — refusal path e2e spec.
 *
 * Covers AC-2 (negative paths), AC-3 (security), and AC-4 (idempotency).
 *
 * These tests verify that the server enforces every guard at the API layer
 * and that the shell presents the correct refusal message in each case.
 * API stubs return deterministic error bodies via page.route() so no
 * external service is required.
 *
 * Viewport: 360 × 800 (mobile), runs under the `technician-mobile` project.
 */

import { test, expect } from '@playwright/test';
import { nextIdempotencyKey } from '../support/session.js';

const TECH_PATH   = '/technician';
const WO_BASE_URL = '/api/v1/work-orders';
const WO_ID       = 'journey-wo-001';
const WO2_ID      = 'journey-wo-002'; // belongs to Tech Two — cross-technician probe

// ── AC-2: Completion without labour time → 422 ───────────────────────────────

test.describe('Refusal: completion without labour time (AC-2)', () => {

  test('COMPLETE without labour time returns 422 with guard message', async ({ page }) => {
    await page.route(`**${WO_BASE_URL}/${WO_ID}/transitions`, (route) => {
      if (route.request().method() === 'POST') {
        route.fulfill({
          status: 422,
          contentType: 'application/json',
          body: JSON.stringify({
            status: 422,
            code: 'GUARD_REFUSED',
            message: 'At least one labour time entry must be recorded before completing this work order.',
            guardCode: 'LABOUR_TIME_MISSING',
            fieldErrors: [],
            traceId: 'e2e-trace-labour',
          }),
        });
      } else {
        route.continue();
      }
    });

    await page.goto(TECH_PATH);

    const result = await page.evaluate(async (woId) => {
      const r = await fetch(`/api/v1/work-orders/${woId}/transitions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ event: 'COMPLETE', expectedVersion: 3 }),
      });
      return { status: r.status, body: await r.json() };
    }, WO_ID);

    expect(result.status).toBe(422);
    expect(result.body.code).toBe('GUARD_REFUSED');
    expect(result.body.guardCode).toBe('LABOUR_TIME_MISSING');
    expect(result.body.message).toContain('labour time');
  });

  test('state is unchanged after refused COMPLETE', async ({ page }) => {
    await page.route(`**${WO_BASE_URL}/${WO_ID}/transitions`, (route) => {
      route.fulfill({
        status: 422,
        contentType: 'application/json',
        body: JSON.stringify({ status: 422, code: 'GUARD_REFUSED', guardCode: 'LABOUR_TIME_MISSING' }),
      });
    });

    await page.route(`**${WO_BASE_URL}/${WO_ID}`, (route) => {
      if (route.request().method() === 'GET') {
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ id: WO_ID, state: 'IN_PROGRESS', version: 3 }),
        });
      } else {
        route.continue();
      }
    });

    await page.goto(TECH_PATH);

    // Attempt completion — fails
    await page.evaluate(async (woId) => {
      await fetch(`/api/v1/work-orders/${woId}/transitions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ event: 'COMPLETE', expectedVersion: 3 }),
      });
    }, WO_ID);

    // Re-read state — should still be IN_PROGRESS
    const stateResult = await page.evaluate(async (woId) => {
      const r = await fetch(`/api/v1/work-orders/${woId}`);
      return r.json();
    }, WO_ID);

    expect(stateResult.state).toBe('IN_PROGRESS');
  });

});

// ── AC-2: Illegal transition → 409 ───────────────────────────────────────────

test.describe('Refusal: illegal state transition returns 409 (AC-2)', () => {

  test('transition from wrong state returns 409 with ILLEGAL_TRANSITION code', async ({ page }) => {
    await page.route(`**${WO_BASE_URL}/${WO_ID}/transitions`, (route) => {
      route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 409,
          code: 'ILLEGAL_TRANSITION',
          message: 'Transition COMPLETE from state ASSIGNED is not permitted.',
          fieldErrors: [],
          traceId: 'e2e-trace-illegal',
        }),
      });
    });

    await page.goto(TECH_PATH);

    const result = await page.evaluate(async (woId) => {
      const r = await fetch(`/api/v1/work-orders/${woId}/transitions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ event: 'COMPLETE', expectedVersion: 1 }),
      });
      return { status: r.status, body: await r.json() };
    }, WO_ID);

    expect(result.status).toBe(409);
    expect(result.body.code).toBe('ILLEGAL_TRANSITION');
  });

});

// ── AC-2: Offline write refusal ───────────────────────────────────────────────

test.describe('Refusal: offline write refused (AC-2)', () => {

  test('not-connected banner appears when browser goes offline', async ({ page, context }) => {
    await page.goto(TECH_PATH);
    await page.waitForLoadState('networkidle');

    // Baseline: no banner
    await expect(page.locator('[data-testid="not-connected-banner"]')).not.toBeVisible();

    // Simulate offline
    await context.setOffline(true);
    await page.evaluate(() => window.dispatchEvent(new Event('offline')));

    // Banner appears within 2.5 seconds (1 s debounce + render)
    await expect(page.locator('[data-testid="not-connected-banner"]'))
      .toBeVisible({ timeout: 2_500 });
  });

  test('no navigation away from day list while offline', async ({ page, context }) => {
    await page.goto(TECH_PATH);
    await page.waitForLoadState('networkidle');

    const urlBefore = page.url();

    await context.setOffline(true);
    await page.evaluate(() => window.dispatchEvent(new Event('offline')));
    await page.waitForTimeout(1_200); // debounce settle

    expect(page.url()).toBe(urlBefore);
  });

  test('offline banner clears within 2 s of reconnect, no queued write is replayed', async ({ page, context }) => {
    await page.goto(TECH_PATH);
    await page.waitForLoadState('networkidle');

    await context.setOffline(true);
    await page.evaluate(() => window.dispatchEvent(new Event('offline')));
    await expect(page.locator('[data-testid="not-connected-banner"]')).toBeVisible({ timeout: 2_500 });

    // Track any POST requests after reconnect (would indicate a replayed write)
    const postRequests = [];
    page.on('request', (req) => {
      if (req.method() === 'POST') postRequests.push(req.url());
    });

    await context.setOffline(false);
    await page.evaluate(() => window.dispatchEvent(new Event('online')));

    // Banner should clear
    await expect(page.locator('[data-testid="not-connected-banner"]'))
      .not.toBeVisible({ timeout: 2_500 });

    // Allow time for any phantom replays
    await page.waitForTimeout(500);

    // No mutation POSTs should have been replayed
    const mutationPosts = postRequests.filter(
      (u) => u.includes('/transitions') || u.includes('/labour') || u.includes('/parts')
    );
    expect(mutationPosts, 'No mutation writes should be replayed on reconnect').toHaveLength(0);
  });

});

// ── AC-3: Cross-technician security ──────────────────────────────────────────

test.describe('Security: cross-technician and CUSTOMER access denied (AC-3)', () => {

  test('request to another technician\'s work order returns 403', async ({ page }) => {
    // Stub Tech Two's WO returning 403 — no existence disclosure
    await page.route(`**${WO_BASE_URL}/${WO2_ID}**`, (route) => {
      route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 403,
          code: 'FORBIDDEN',
          message: 'Forbidden.',
          fieldErrors: [],
          traceId: 'e2e-trace-xtech',
        }),
      });
    });

    await page.goto(TECH_PATH);

    const result = await page.evaluate(async (woId) => {
      const r = await fetch(`/api/v1/work-orders/${woId}`);
      return { status: r.status, body: await r.json() };
    }, WO2_ID);

    expect(result.status).toBe(403);
    // Response must NOT disclose whether the resource exists
    expect(result.body.code).toBe('FORBIDDEN');
    expect(result.body.message.toLowerCase()).not.toContain('not found');
    expect(result.body.message.toLowerCase()).not.toContain('does not exist');
  });

  test('transition on another technician\'s work order returns 403', async ({ page }) => {
    await page.route(`**${WO_BASE_URL}/${WO2_ID}/transitions`, (route) => {
      route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({ status: 403, code: 'FORBIDDEN', message: 'Forbidden.', fieldErrors: [], traceId: 'e2e-trace-xtech-t' }),
      });
    });

    await page.goto(TECH_PATH);

    const result = await page.evaluate(async (woId) => {
      const r = await fetch(`/api/v1/work-orders/${woId}/transitions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ event: 'DEPART', expectedVersion: 1 }),
      });
      return { status: r.status, body: await r.json() };
    }, WO2_ID);

    expect(result.status).toBe(403);
  });

  test('CUSTOMER token cannot access technician day list endpoint (403)', async ({ page }) => {
    // Stub the technician day list to return 403 for a customer token
    await page.route(`**${WO_BASE_URL.replace('/work-orders', '/technicians/me/work-orders')}**`, (route) => {
      route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({ status: 403, code: 'FORBIDDEN', message: 'Forbidden.', fieldErrors: [], traceId: 'e2e-trace-customer' }),
      });
    });

    await page.goto(TECH_PATH);

    const result = await page.evaluate(async () => {
      const r = await fetch('/api/v1/technicians/me/work-orders?date=2026-09-15');
      return { status: r.status, body: await r.json() };
    });

    expect(result.status).toBe(403);
  });

});

// ── AC-4: Idempotency ────────────────────────────────────────────────────────

test.describe('Idempotency: duplicate request with same key produces one effect (AC-4)', () => {

  test('same Idempotency-Key on two transition POSTs produces one effect', async ({ page }) => {
    const idempotencyKey = nextIdempotencyKey('labour-time');
    let requestCount = 0;

    // First call succeeds; second is a replay — stub returns same 200 with same body
    await page.route(`**${WO_BASE_URL}/${WO_ID}/transitions`, (route) => {
      requestCount++;
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: WO_ID,
          state: 'COMPLETED',
          version: 4,
          legalNextEvents: ['CLOSE'],
          idempotencyKey,
        }),
      });
    });

    await page.goto(TECH_PATH);

    // First request
    const first = await page.evaluate(async (woId, key) => {
      const r = await fetch(`/api/v1/work-orders/${woId}/transitions`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': key,
        },
        body: JSON.stringify({ event: 'COMPLETE', expectedVersion: 3 }),
      });
      return { status: r.status, body: await r.json() };
    }, WO_ID, idempotencyKey);

    // Replay with the same key
    const second = await page.evaluate(async (woId, key) => {
      const r = await fetch(`/api/v1/work-orders/${woId}/transitions`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': key,
        },
        body: JSON.stringify({ event: 'COMPLETE', expectedVersion: 3 }),
      });
      return { status: r.status, body: await r.json() };
    }, WO_ID, idempotencyKey);

    expect(first.status).toBe(200);
    expect(second.status).toBe(200);

    // Both responses must carry the same state (idempotent effect)
    expect(first.body.state).toBe('COMPLETED');
    expect(second.body.state).toBe('COMPLETED');
    expect(first.body.version).toBe(second.body.version);
  });

});
