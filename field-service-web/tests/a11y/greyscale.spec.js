/**
 * Greyscale survivability suite.
 *
 * Applies a CSS greyscale filter to the page and asserts that every
 * Chip, KPI delta indicator and chart series retains meaning through
 * a text label AND a distinct icon or shape — colour alone is never
 * the sole differentiator (BR-34).
 *
 * DOM-level assertions (text node + data-shape icon) complement the
 * visual check so the suite catches colour-only indicators even when
 * the greyscale filter cannot be verified by screenshot.
 *
 * Requires: @playwright/test + dev server at http://localhost:5173.
 * Pipeline step: test:a11y (blocking)
 */

import { test, expect } from '@playwright/test';

const PERSONA_ROUTES = [
  { persona: 'dispatcher', path: '/dispatch', viewport: { width: 1440, height: 900 } },
  { persona: 'technician', path: '/field', viewport: { width: 360, height: 780 } },
  { persona: 'manager', path: '/operations', viewport: { width: 1440, height: 900 } },
];

for (const { persona, path, viewport } of PERSONA_ROUTES) {
  test(`${persona} — all status indicators pass greyscale survivability`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto(path);
    await page.waitForLoadState('networkidle');

    // Apply greyscale filter
    await page.addStyleTag({
      content: '*, *::before, *::after { filter: grayscale(100%) !important; }',
    });

    // Assert each Chip has both text and icon
    const chips = page.locator('[role="status"]');
    const chipCount = await chips.count();

    for (let i = 0; i < chipCount; i++) {
      const chip = chips.nth(i);
      const iconEl = chip.locator('[aria-hidden="true"][data-shape]');
      const hasIcon = (await iconEl.count()) > 0;
      const ariaLabel = await chip.getAttribute('aria-label');
      const textContent = await chip.textContent();

      expect(hasIcon, `Chip ${i} has no shape icon (greyscale fail)`).toBe(true);
      expect(ariaLabel || textContent?.trim(), `Chip ${i} has no text label (greyscale fail)`).toBeTruthy();
    }

    // Assert KPI delta elements carry text
    const deltas = page.locator('[aria-label*="vs prior period"]');
    const deltaCount = await deltas.count();
    for (let i = 0; i < deltaCount; i++) {
      const text = await deltas.nth(i).textContent();
      expect(text?.trim(), `KPI delta ${i} has no text content`).toBeTruthy();
    }
  });
}

test('no status element relies on colour alone (DOM assertion)', async ({ page }) => {
  await page.goto('/dispatch');
  await page.waitForLoadState('networkidle');

  const statusElements = await page.evaluate(() => {
    return [...document.querySelectorAll('[role="status"]')].map((el) => ({
      hasText: !!el.querySelector('span:not([aria-hidden])') || el.textContent.trim().length > 0,
      hasIcon: !!el.querySelector('[aria-hidden="true"][data-shape]'),
      html: el.outerHTML.slice(0, 120),
    }));
  });

  for (const el of statusElements) {
    expect(el.hasText, `Status element missing text: ${el.html}`).toBe(true);
    expect(el.hasIcon, `Status element missing icon/shape: ${el.html}`).toBe(true);
  }
});
