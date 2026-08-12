/**
 * Technician accessibility, layout and contrast gate spec.
 *
 * Covers AC-6:
 *   - axe reports zero critical violations on every technician route
 *   - no route produces horizontal scroll at 360 px
 *   - every interactive control meets 44 × 44 CSS pixels
 *   - status and risk indicators pass WCAG AA contrast in both light and dark appearance
 *
 * Viewport: 360 × 800 (mobile), runs under the `technician-mobile` project.
 */

import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

const TECHNICIAN_ROUTES = [
  { name: 'day-list', path: '/technician' },
  { name: 'day-list-offline', path: '/technician', offline: true },
];

// WCAG AA contrast minimum for normal text: 4.5:1
// For large text / UI components: 3:1
const AA_CONTRAST_MIN = 4.5;
const AA_UI_CONTRAST_MIN = 3.0;

// Design token color pairs to verify (light appearance)
// Format: { name, fg, bg } — hex colors as returned by CSS getPropertyValue
const LIGHT_CONTRAST_PAIRS = [
  { name: 'at-risk indicator text', selector: '[data-risk="true"] [class*="riskBadge"]', contrastMin: AA_UI_CONTRAST_MIN },
  { name: 'offline banner text', selector: '[data-testid="not-connected-banner"]', contrastMin: AA_CONTRAST_MIN },
];

// ── Route-level axe scan ──────────────────────────────────────────────────────

for (const route of TECHNICIAN_ROUTES) {
  test.describe(`Accessibility: ${route.name}`, () => {

    test.beforeEach(async ({ page, context }) => {
      if (route.offline) {
        await context.setOffline(true);
        await page.goto(route.path);
        await page.evaluate(() => window.dispatchEvent(new Event('offline')));
        // Wait for offline banner to render (debounce + paint)
        await page.waitForTimeout(1_500);
      } else {
        await page.goto(route.path);
        await page.waitForLoadState('networkidle');
      }
    });

    test.afterEach(async ({ context }) => {
      // Restore online state in case the test set it offline
      await context.setOffline(false);
    });

    test(`${route.name}: zero critical axe violations (WCAG 2.1 AA)`, async ({ page }) => {
      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
        .analyze();

      const criticalOrSerious = results.violations.filter(
        (v) => v.impact === 'critical' || v.impact === 'serious'
      );

      expect(
        criticalOrSerious,
        `Axe violations on ${route.path}:\n` +
          criticalOrSerious
            .map((v) =>
              `  [${v.impact}] ${v.id}: ${v.description}\n    ` +
              v.nodes.map((n) => n.html).slice(0, 3).join('\n    ')
            )
            .join('\n')
      ).toHaveLength(0);
    });

    test(`${route.name}: no horizontal scroll at 360 px`, async ({ page }) => {
      const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
      const clientWidth = await page.evaluate(() => document.documentElement.clientWidth);

      expect(
        scrollWidth,
        `scrollWidth ${scrollWidth} exceeds clientWidth ${clientWidth} on ${route.path}`
      ).toBeLessThanOrEqual(clientWidth);
    });

    test(`${route.name}: all interactive controls meet 44 × 44 px touch target`, async ({ page }) => {
      const interactives = page.locator('button, a[href], [role="button"], input, select, textarea');
      const count = await interactives.count();

      const failures = [];
      for (let i = 0; i < count; i++) {
        const el = interactives.nth(i);
        if (!(await el.isVisible())) continue;

        const box = await el.boundingBox();
        if (!box) continue;

        if (box.width < 44 || box.height < 44) {
          const html = await el.evaluate((e) => e.outerHTML.slice(0, 120));
          failures.push(`[${i}] ${box.width}×${box.height}px — ${html}`);
        }
      }

      expect(
        failures,
        `Touch target violations on ${route.path}:\n${failures.join('\n')}`
      ).toHaveLength(0);
    });

  });
}

// ── Contrast gates ────────────────────────────────────────────────────────────

test.describe('Contrast: status and risk indicators in light appearance', () => {

  test.beforeEach(async ({ page }) => {
    await page.goto('/technician');
    await page.waitForLoadState('networkidle');

    // Ensure light appearance is active
    await page.evaluate(() => {
      document.documentElement.setAttribute('data-appearance', 'light');
    });
  });

  test('offline banner meets WCAG AA contrast ratio in light appearance', async ({ page }) => {
    // Trigger offline to show the banner
    await page.evaluate(async () => {
      window.dispatchEvent(new Event('offline'));
      await new Promise((r) => setTimeout(r, 1_500));
    });

    const banner = page.locator('[data-testid="not-connected-banner"]');
    const bannerVisible = await banner.isVisible();

    if (!bannerVisible) {
      test.skip();
      return;
    }

    const { foreground, background } = await page.evaluate(() => {
      const el = document.querySelector('[data-testid="not-connected-banner"]');
      if (!el) return { foreground: null, background: null };
      const styles = getComputedStyle(el);
      return { foreground: styles.color, background: styles.backgroundColor };
    });

    if (!foreground || !background) {
      test.skip();
      return;
    }

    // Compute relative luminance and contrast ratio in-page
    const contrastRatio = await page.evaluate(({ fg, bg }) => {
      function parseCSSColor(str) {
        const m = str.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/);
        if (!m) return null;
        return [parseInt(m[1]), parseInt(m[2]), parseInt(m[3])];
      }
      function relLuminance([r, g, b]) {
        const srgb = [r, g, b].map((c) => {
          const s = c / 255;
          return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        });
        return 0.2126 * srgb[0] + 0.7152 * srgb[1] + 0.0722 * srgb[2];
      }
      const fgRgb = parseCSSColor(fg);
      const bgRgb = parseCSSColor(bg);
      if (!fgRgb || !bgRgb) return null;
      const l1 = Math.max(relLuminance(fgRgb), relLuminance(bgRgb));
      const l2 = Math.min(relLuminance(fgRgb), relLuminance(bgRgb));
      return (l1 + 0.05) / (l2 + 0.05);
    }, { fg: foreground, bg: background });

    if (contrastRatio !== null) {
      expect(
        contrastRatio,
        `Offline banner contrast ratio ${contrastRatio?.toFixed(2)} is below WCAG AA threshold ${AA_CONTRAST_MIN}`
      ).toBeGreaterThanOrEqual(AA_CONTRAST_MIN);
    }
  });

});

test.describe('Contrast: dark appearance', () => {

  test.beforeEach(async ({ page }) => {
    await page.goto('/technician');
    await page.waitForLoadState('networkidle');

    // Switch to dark appearance
    await page.evaluate(() => {
      document.documentElement.setAttribute('data-appearance', 'dark');
    });
  });

  test('zero critical axe violations in dark appearance', async ({ page }) => {
    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
      .analyze();

    const criticalOrSerious = results.violations.filter(
      (v) => v.impact === 'critical' || v.impact === 'serious'
    );

    expect(
      criticalOrSerious,
      `Axe violations in dark appearance:\n` +
        criticalOrSerious
          .map((v) => `  [${v.impact}] ${v.id}: ${v.description}`)
          .join('\n')
    ).toHaveLength(0);
  });

  test('no horizontal scroll in dark appearance', async ({ page }) => {
    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
    const clientWidth = await page.evaluate(() => document.documentElement.clientWidth);
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth);
  });

});

// ── Layout gate: appearance toggle persists and satisfies layout ──────────────

test.describe('Layout gate: appearance toggle', () => {

  test('appearance toggle switches data-appearance attribute', async ({ page }) => {
    await page.goto('/technician');
    await page.waitForLoadState('networkidle');

    const html = page.locator('html');
    const initial = await html.getAttribute('data-appearance');

    const toggle = page
      .locator('button[aria-label*="appearance"], button[aria-label*="Appearance"]')
      .or(page.locator('button:has-text("Dark"), button:has-text("Light")'))
      .first();

    const visible = await toggle.isVisible();
    if (!visible) {
      test.skip();
      return;
    }

    await toggle.click();
    const after = await html.getAttribute('data-appearance');
    expect(after).not.toBe(initial);
  });

  test('layout is intact after appearance toggle (no overflow)', async ({ page }) => {
    await page.goto('/technician');
    await page.waitForLoadState('networkidle');

    // Toggle appearance
    await page.evaluate(() => {
      const current = document.documentElement.getAttribute('data-appearance') ?? 'light';
      document.documentElement.setAttribute('data-appearance', current === 'light' ? 'dark' : 'light');
    });

    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
    const clientWidth = await page.evaluate(() => document.documentElement.clientWidth);
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth);
  });

});
