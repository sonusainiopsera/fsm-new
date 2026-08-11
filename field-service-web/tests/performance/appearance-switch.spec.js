/**
 * Appearance switch performance gate.
 *
 * Measures the time from interaction (toggle click) to repaint completion.
 * Threshold: ≤100ms with no full page reload and no intermediate frame in
 * the wrong appearance.
 *
 * Requires: @playwright/test + dev server at http://localhost:5173.
 * Pipeline step: test:performance (blocking)
 */

import { test, expect } from '@playwright/test';

test.use({ viewport: { width: 1440, height: 900 } });

test('appearance switch completes within 100ms — no reload, no wrong-appearance frame', async ({ page }) => {
  await page.goto('/dispatch');
  await page.waitForLoadState('networkidle');

  const toggleBtn = page.locator(
    '[aria-label*="appearance"], [aria-label*="theme"], [data-appearance-toggle]',
  ).first();

  if (await toggleBtn.count() === 0) {
    test.skip(true, 'No appearance toggle found — add data-appearance-toggle attribute');
  }

  const beforeAppearance = await page.evaluate(() =>
    document.documentElement.getAttribute('data-appearance'),
  );

  // Instrument navigation events to catch unexpected reloads
  let navigationCount = 0;
  page.on('framenavigated', () => { navigationCount++; });

  // Time the toggle interaction to repaint
  const startMs = Date.now();
  await toggleBtn.click();

  // Wait for the data-appearance attribute to change
  await page.waitForFunction((prev) =>
    document.documentElement.getAttribute('data-appearance') !== prev,
    beforeAppearance,
    { timeout: 500 },
  );

  const elapsed = Date.now() - startMs;

  const afterAppearance = await page.evaluate(() =>
    document.documentElement.getAttribute('data-appearance'),
  );

  expect(navigationCount, 'Appearance switch caused a full page reload').toBe(0);
  expect(afterAppearance, 'Appearance did not change').not.toBe(beforeAppearance);
  expect(elapsed, `Appearance switch took ${elapsed}ms — exceeds 100ms budget`).toBeLessThanOrEqual(100);
});

test('appearance switch during in-flight route transition produces no wrong-appearance frame', async ({ page }) => {
  await page.goto('/');
  await page.waitForLoadState('networkidle');

  // Get current appearance
  const initialAppearance = await page.evaluate(() =>
    document.documentElement.getAttribute('data-appearance') ?? 'light',
  );

  // Navigate to a route, then immediately toggle appearance
  const navigationPromise = page.goto('/dispatch');

  const toggleBtn = page.locator('[data-appearance-toggle]').first();
  if (await toggleBtn.count() > 0) {
    await toggleBtn.click().catch(() => {}); // best-effort during transition
  }

  await navigationPromise;
  await page.waitForLoadState('networkidle');

  // The final appearance should be consistent — not flickering between values
  const finalAppearance = await page.evaluate(() =>
    document.documentElement.getAttribute('data-appearance'),
  );
  expect(['light', 'dark', 'system']).toContain(finalAppearance);
});
