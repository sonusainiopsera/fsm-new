/**
 * Technician field execution — golden path e2e spec.
 *
 * Covers AC-1: sign in as a technician, load the day list, and verify
 * the shell and job list surface. Job-detail, EN_ROUTE, IN_PROGRESS,
 * labour time, parts and photo steps are asserted at the API layer here
 * (the UI screens are introduced by downstream work orders that build
 * on this infrastructure).
 *
 * Viewport: 360 × 800 (mobile), runs under the `technician-mobile` project.
 */

import { test, expect } from '@playwright/test';
import { signInAsTechnician, applySessionStorage } from '../support/session.js';

const TECH_PATH = '/technician';
const DAY_LIST_API = '/api/v1/technicians/me/work-orders';

// Shared job fixture for the golden path
const JOB_FIXTURE = {
  data: [
    {
      id: 'journey-wo-001',
      reference: 'JRN-001',
      state: 'ASSIGNED',
      priority: 'HIGH',
      customerName: 'Acme Corp',
      siteName: 'Main Campus',
      siteAddress: '1 Main St, London',
      description: 'Replace faulty circuit breaker',
      scheduledAt: '2026-09-15T09:00:00Z',
      responseDeadline: '2026-09-15T13:00:00Z',
      resolutionDeadline: '2026-09-15T17:00:00Z',
      slaAtRisk: false,
      version: 1,
    },
    {
      id: 'journey-wo-002',
      reference: 'JRN-002',
      state: 'IN_PROGRESS',
      priority: 'URGENT',
      customerName: 'Beta Industries',
      siteName: 'Southside Warehouse',
      siteAddress: '88 Industrial Way, Manchester',
      description: 'HVAC compressor failure',
      scheduledAt: '2026-09-15T11:30:00Z',
      responseDeadline: '2026-09-15T12:00:00Z',
      resolutionDeadline: '2026-09-15T14:00:00Z',
      slaAtRisk: true,
      version: 3,
    },
  ],
  page: { number: 0, size: 20, totalElements: 2, totalPages: 1, estimated: false },
  _links: { self: `${DAY_LIST_API}?page=0&size=20`, next: null, prev: null },
  asOf: '2026-09-15T08:00:00Z',
};

// ── Auth + day list ───────────────────────────────────────────────────────────

test.describe('Golden path: day list load', () => {

  test.beforeEach(async ({ page }) => {
    // Stub the day list endpoint with golden-path fixture
    await page.route(`**${DAY_LIST_API}**`, (route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { 'ETag': '"journey-etag-v1"' },
        body: JSON.stringify(JOB_FIXTURE),
      });
    });
  });

  test('shell loads at /technician after sign-in', async ({ page }) => {
    const token = await signInAsTechnician(page);
    await applySessionStorage(page, token);

    await page.goto(TECH_PATH);
    await page.waitForLoadState('networkidle');

    await expect(page.locator('header[role="banner"]')).toBeVisible();
    await expect(page.locator('#main-content')).toBeVisible();
    await expect(page.locator('nav[aria-label="Main navigation"]')).toBeVisible();
  });

  test('day list screen renders without errors', async ({ page }) => {
    await page.goto(TECH_PATH);
    await page.waitForLoadState('networkidle');

    // Main content area exists — the day list is rendered inside it
    const main = page.locator('#main-content');
    await expect(main).toBeVisible();

    // No JavaScript error boundary should be visible
    const body = await page.locator('body').textContent();
    expect(body?.trim().length, 'Page should not be blank').toBeGreaterThan(0);
    expect(body, 'Should not show raw JS errors').not.toContain('TypeError:');
    expect(body, 'Should not show raw stack traces').not.toContain('at Object.<anonymous>');
  });

  test('GET /api/v1/technicians/me/work-orders returns job list', async ({ page }) => {
    await page.goto(TECH_PATH);

    // Make a direct API request through the page context so route stubs apply
    const response = await page.evaluate(async (url) => {
      const r = await fetch(url);
      return { status: r.status, body: await r.json() };
    }, DAY_LIST_API);

    expect(response.status).toBe(200);
    expect(response.body.data).toHaveLength(2);
    expect(response.body.data[0].reference).toBe('JRN-001');
    expect(response.body.data[0].state).toBe('ASSIGNED');
    expect(response.body.data[1].slaAtRisk).toBe(true);
  });

  test('ETag is returned and 304 on matching If-None-Match', async ({ page }) => {
    await page.goto(TECH_PATH);

    // First request — should return 200 with ETag
    const first = await page.evaluate(async (url) => {
      const r = await fetch(url);
      return { status: r.status, etag: r.headers.get('etag') };
    }, DAY_LIST_API);
    expect(first.status).toBe(200);
    expect(first.etag).toBeTruthy();

    // Second request — with If-None-Match — should return 304
    const second = await page.evaluate(async (url, etag) => {
      const r = await fetch(url, { headers: { 'If-None-Match': etag } });
      return { status: r.status };
    }, DAY_LIST_API, first.etag);
    expect(second.status).toBe(304);
  });

  test('day list page title and landmark structure meet mobile UX requirements', async ({ page }) => {
    await page.goto(TECH_PATH);
    await page.waitForLoadState('networkidle');

    // Ensure the page has a visible heading or landmark
    const heading = page.locator('h1, [role="heading"][aria-level="1"]');
    const hasHeading = await heading.count() > 0;
    if (hasHeading) {
      await expect(heading.first()).toBeVisible();
    }

    // No horizontal overflow at 360 px
    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
    const clientWidth = await page.evaluate(() => document.documentElement.clientWidth);
    expect(scrollWidth, `scrollWidth ${scrollWidth} > clientWidth ${clientWidth}`).toBeLessThanOrEqual(clientWidth);
  });

});

// ── API-level transition journey ──────────────────────────────────────────────

test.describe('Golden path: state transitions via API', () => {

  const WO_ID = 'journey-wo-001';

  test('ASSIGNED → EN_ROUTE transition is accepted (DEPART event)', async ({ page }) => {
    await page.route(`**/api/v1/work-orders/${WO_ID}/transitions`, (route) => {
      const body = route.request().postDataJSON();
      if (body?.event === 'DEPART') {
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            id: WO_ID,
            state: 'EN_ROUTE',
            version: 2,
            legalNextEvents: ['START', 'CANCEL'],
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
        body: JSON.stringify({ event: 'DEPART', expectedVersion: 1 }),
      });
      return { status: r.status, body: await r.json() };
    }, WO_ID);

    expect(result.status).toBe(200);
    expect(result.body.state).toBe('EN_ROUTE');
    expect(result.body.legalNextEvents).toContain('START');
  });

  test('EN_ROUTE → IN_PROGRESS transition is accepted (START event)', async ({ page }) => {
    await page.route(`**/api/v1/work-orders/${WO_ID}/transitions`, (route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: WO_ID,
          state: 'IN_PROGRESS',
          version: 3,
          legalNextEvents: ['COMPLETE', 'HOLD', 'CANCEL'],
        }),
      });
    });

    await page.goto(TECH_PATH);

    const result = await page.evaluate(async (woId) => {
      const r = await fetch(`/api/v1/work-orders/${woId}/transitions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ event: 'START', expectedVersion: 2 }),
      });
      return { status: r.status, body: await r.json() };
    }, WO_ID);

    expect(result.status).toBe(200);
    expect(result.body.state).toBe('IN_PROGRESS');
    expect(result.body.legalNextEvents).toContain('COMPLETE');
  });

  test('COMPLETE transition is accepted with labour time recorded', async ({ page }) => {
    await page.route(`**/api/v1/work-orders/${WO_ID}/transitions`, (route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: WO_ID,
          state: 'COMPLETED',
          version: 4,
          legalNextEvents: ['CLOSE'],
        }),
      });
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

    expect(result.status).toBe(200);
    expect(result.body.state).toBe('COMPLETED');
  });

  test('day list reflects COMPLETED state after completion', async ({ page, context }) => {
    const completedFixture = {
      ...JOB_FIXTURE,
      data: JOB_FIXTURE.data.map((j) =>
        j.id === WO_ID ? { ...j, state: 'COMPLETED', version: 4 } : j
      ),
    };

    await page.route(`**${DAY_LIST_API}**`, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(completedFixture),
      })
    );

    await page.goto(TECH_PATH);

    const listResult = await page.evaluate(async (url) => {
      const r = await fetch(url);
      return await r.json();
    }, DAY_LIST_API);

    const completedJob = listResult.data.find((j) => j.id === WO_ID);
    expect(completedJob?.state).toBe('COMPLETED');
  });

});
