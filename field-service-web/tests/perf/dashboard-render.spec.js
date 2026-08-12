/**
 * Performance harness: first meaningful render of the Operations Dashboard.
 *
 * Measures how long it takes for the KPI widget grid to be visible after
 * navigation, using a 90-day fixture for the heaviest data set.
 *
 * Gate: p95 across 5 runs must be ≤ 2000 ms.
 *
 * Run locally:
 *   npx playwright test tests/perf/dashboard-render.spec.js
 *
 * The test uses Playwright's performance trace — no Lighthouse dependency.
 */

const { test, expect } = require('@playwright/test');

const RUNS     = 5;
const P95_MS   = 2000;
const SELECTOR = '[role="list"]'; // WidgetGrid renders as role=list

/**
 * Measures the time from navigation start to the widget grid being visible.
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<number>} elapsed milliseconds
 */
async function measureFirstMeaningfulRender(page) {
  const start = Date.now();
  await page.goto('/operations/dashboard?window=90d', { waitUntil: 'networkidle' });

  // Wait for widget grid to appear — this is the "first meaningful render"
  await page.waitForSelector(SELECTOR, { state: 'visible', timeout: 5000 });

  // Additionally wait until loading skeletons are gone (real data visible)
  await page.waitForFunction(
    (sel) => !document.querySelector('[aria-label="Loading widget"]'),
    SELECTOR,
    { timeout: 5000 },
  );

  return Date.now() - start;
}

/**
 * Calculates p95 from a sorted array of durations.
 *
 * @param {number[]} samples
 * @returns {number}
 */
function p95(samples) {
  const sorted = [...samples].sort((a, b) => a - b);
  const idx    = Math.ceil(samples.length * 0.95) - 1;
  return sorted[Math.max(0, idx)];
}

test.describe('Dashboard first-meaningful-render perf harness', () => {
  test(`p95 FMR ≤ ${P95_MS}ms over ${RUNS} runs (90-day fixture)`, async ({ page }) => {
    const durations = [];

    for (let i = 0; i < RUNS; i++) {
      const ms = await measureFirstMeaningfulRender(page);
      durations.push(ms);
      console.log(`  Run ${i + 1}/${RUNS}: ${ms}ms`);
    }

    const p95ms = p95(durations);
    console.log(`  p95: ${p95ms}ms  (gate: ${P95_MS}ms)`);
    console.log(`  min: ${Math.min(...durations)}ms  max: ${Math.max(...durations)}ms`);

    expect(p95ms).toBeLessThanOrEqual(P95_MS);
  });

  test('widget grid is visible within 2s on first cold navigation (90-day)', async ({ page }) => {
    const ms = await measureFirstMeaningfulRender(page);
    expect(ms).toBeLessThanOrEqual(P95_MS);
  });
});
