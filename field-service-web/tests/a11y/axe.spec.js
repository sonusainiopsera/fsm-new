/**
 * Accessibility gate — axe-core screen-level checks.
 *
 * Runs axe-core against composed persona screens in BOTH appearances.
 * A violation present in only one appearance fails the build (WCAG 2.1 AA).
 *
 * Requires: @playwright/test + @axe-core/playwright installed and a dev server
 * running at http://localhost:5173.  Add fixtures to src/mocks/fixtures/screens/
 * to extend coverage without a backend dependency.
 *
 * Pipeline step: test:a11y (blocking)
 */

import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

const APPEARANCES = ['light', 'dark'];
const PERSONA_ROUTES = [
  { persona: 'dispatcher', path: '/dispatch', viewport: { width: 1440, height: 900 } },
  { persona: 'technician', path: '/field', viewport: { width: 360, height: 780 } },
  { persona: 'manager', path: '/operations', viewport: { width: 1440, height: 900 } },
  { persona: 'customer', path: '/portal', viewport: { width: 768, height: 1024 } },
];

for (const appearance of APPEARANCES) {
  for (const { persona, path, viewport } of PERSONA_ROUTES) {
    test(`${persona} [${appearance}] — no critical/serious axe violations`, async ({ page }) => {
      await page.setViewportSize(viewport);

      // Set appearance before navigation to avoid flash
      await page.addInitScript((app) => {
        document.documentElement.setAttribute('data-appearance', app);
      }, appearance);

      await page.goto(path);
      await page.waitForLoadState('networkidle');

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
        .analyze();

      const violations = results.violations.filter(
        (v) => v.impact === 'critical' || v.impact === 'serious',
      );

      expect(violations, `Axe violations [${persona} / ${appearance}]:\n` +
        violations.map((v) => `  ${v.id}: ${v.description}\n    ${v.nodes.map(n => n.html).join('\n    ')}`).join('\n')
      ).toHaveLength(0);
    });
  }
}
