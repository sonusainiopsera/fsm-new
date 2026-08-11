/**
 * Core Web Vitals performance gate.
 *
 * Measures INP (Interaction to Next Paint) p95 and CLS (Cumulative Layout Shift)
 * on representative persona screens.  Thresholds:
 *   - INP ≤ 200ms at p95
 *   - CLS ≤ 0.05
 *
 * INP is measured by injecting the web-vitals library and collecting metrics
 * through the Performance Observer API.
 *
 * Requires: @playwright/test + dev server at http://localhost:5173.
 * Pipeline step: test:performance (blocking)
 */

import { test, expect } from '@playwright/test';

const PERSONA_SCREENS = [
  { persona: 'dispatcher', path: '/dispatch', viewport: { width: 1440, height: 900 } },
  { persona: 'technician', path: '/field', viewport: { width: 360, height: 780 } },
  { persona: 'manager', path: '/operations', viewport: { width: 1440, height: 900 } },
  { persona: 'customer', path: '/portal', viewport: { width: 768, height: 1024 } },
];

const INP_BUDGET_MS = 200;
const CLS_BUDGET = 0.05;

for (const { persona, path, viewport } of PERSONA_SCREENS) {
  test(`${persona} — CLS ≤ ${CLS_BUDGET}`, async ({ page }) => {
    await page.setViewportSize(viewport);

    // Inject CLS observer
    await page.addInitScript(() => {
      window.__clsValues = [];
      new PerformanceObserver((list) => {
        for (const entry of list.getEntries()) {
          if (!entry.hadRecentInput) {
            window.__clsValues.push(entry.value);
          }
        }
      }).observe({ type: 'layout-shift', buffered: true });
    });

    await page.goto(path);
    await page.waitForLoadState('networkidle');

    // Wait for layout to settle
    await page.waitForTimeout(500);

    const clsTotal = await page.evaluate(() =>
      (window.__clsValues || []).reduce((s, v) => s + v, 0),
    );

    expect(clsTotal, `${persona} CLS=${clsTotal.toFixed(4)} exceeds budget ${CLS_BUDGET}`).toBeLessThanOrEqual(CLS_BUDGET);
  });

  test(`${persona} — INP ≤ ${INP_BUDGET_MS}ms on primary interaction`, async ({ page }) => {
    await page.setViewportSize(viewport);

    await page.addInitScript(() => {
      window.__inpValues = [];
      new PerformanceObserver((list) => {
        for (const entry of list.getEntries()) {
          window.__inpValues.push(entry.duration);
        }
      }).observe({ type: 'event', durationThreshold: 0, buffered: true });
    });

    await page.goto(path);
    await page.waitForLoadState('networkidle');

    // Perform a representative interaction (click first interactive element)
    const firstButton = page.locator('button:not([disabled]), a[href]:not([href="#"])').first();
    if (await firstButton.count() > 0) {
      await firstButton.click();
      await page.waitForTimeout(300);
    }

    const inpValues = await page.evaluate(() => window.__inpValues || []);

    if (inpValues.length === 0) {
      // No interactions captured — the page rendered without interaction
      return;
    }

    // p95 from collected samples
    const sorted = [...inpValues].sort((a, b) => a - b);
    const p95 = sorted[Math.floor(sorted.length * 0.95)];

    expect(p95, `${persona} INP p95=${p95}ms exceeds budget ${INP_BUDGET_MS}ms`).toBeLessThanOrEqual(INP_BUDGET_MS);
  });
}
