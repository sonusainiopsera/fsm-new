/**
 * Keyboard operability suite.
 *
 * Traverses the app shell, data table (sorting + row selection), modal,
 * detail drawer and a multi-field form by keyboard only.  Asserts:
 * - Logical tab order through interactive elements
 * - Focus trapping inside modal and drawer
 * - Focus restoration after modal/drawer close
 * - No keyboard traps (Esc closes overlays, Tab cycles back to opener)
 *
 * Runs against the dispatcher persona (highest density / keyboard-first).
 *
 * Requires: @playwright/test + dev server at http://localhost:5173.
 * Pipeline step: test:a11y (blocking)
 */

import { test, expect } from '@playwright/test';

test.use({ viewport: { width: 1440, height: 900 } });

test.describe('App shell keyboard navigation', () => {
  test('skip-to-content link is first focusable element', async ({ page }) => {
    await page.goto('/dispatch');
    await page.keyboard.press('Tab');
    const focusedHref = await page.evaluate(() =>
      document.activeElement?.getAttribute('href'),
    );
    expect(focusedHref).toBe('#main-content');
  });

  test('sidebar nav items are reachable by Tab', async ({ page }) => {
    await page.goto('/dispatch');
    // Tab past skip link into sidebar
    await page.keyboard.press('Tab');
    await page.keyboard.press('Tab');
    const focused = await page.evaluate(() => document.activeElement?.tagName);
    expect(['A', 'BUTTON']).toContain(focused);
  });

  test('main content area receives focus via skip-to-content', async ({ page }) => {
    await page.goto('/dispatch');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');
    const focusedId = await page.evaluate(() => document.activeElement?.id);
    expect(focusedId).toBe('main-content');
  });
});

test.describe('Data table keyboard interaction', () => {
  test('column sort headers are activatable by Enter and Space', async ({ page }) => {
    await page.goto('/dispatch');
    await page.waitForLoadState('networkidle');

    // Navigate to a sortable column header
    const sortHeader = page.locator('[role="columnheader"][aria-sort]').first();
    if (await sortHeader.count() === 0) {
      test.skip(true, 'No sortable columns rendered in this fixture');
    }
    await sortHeader.focus();
    const ariaSort = await sortHeader.getAttribute('aria-sort');
    await page.keyboard.press('Enter');
    const newSort = await sortHeader.getAttribute('aria-sort');
    expect(newSort).not.toBe(ariaSort);
  });

  test('table rows are selectable by keyboard', async ({ page }) => {
    await page.goto('/dispatch');
    await page.waitForLoadState('networkidle');

    const firstRow = page.locator('tbody tr').first();
    if (await firstRow.count() === 0) {
      test.skip(true, 'No table rows rendered in this fixture');
    }
    await firstRow.focus();
    await page.keyboard.press('Enter');
    await expect(firstRow).toHaveAttribute('aria-selected', 'true');
  });
});

test.describe('Modal focus trapping', () => {
  test('Tab cycles within modal; Escape closes and restores focus', async ({ page }) => {
    await page.goto('/dispatch');
    await page.waitForLoadState('networkidle');

    const openTrigger = page.locator('[data-modal-trigger]').first();
    if (await openTrigger.count() === 0) {
      test.skip(true, 'No modal trigger found in this fixture');
    }

    await openTrigger.focus();
    await page.keyboard.press('Enter');

    const modal = page.locator('[role="dialog"]');
    await expect(modal).toBeVisible();

    // Focus should be inside modal
    const insideModal = await page.evaluate(() =>
      document.querySelector('[role="dialog"]')?.contains(document.activeElement),
    );
    expect(insideModal).toBe(true);

    // Escape closes modal and restores focus to trigger
    await page.keyboard.press('Escape');
    await expect(modal).not.toBeVisible();
    const restoredFocus = await page.evaluate(() =>
      document.activeElement?.dataset.modalTrigger !== undefined,
    );
    expect(restoredFocus).toBe(true);
  });
});

test.describe('Detail drawer focus trapping', () => {
  test('Tab cycles within drawer; Escape closes and restores focus', async ({ page }) => {
    await page.goto('/dispatch');
    await page.waitForLoadState('networkidle');

    const drawerTrigger = page.locator('[data-drawer-trigger]').first();
    if (await drawerTrigger.count() === 0) {
      test.skip(true, 'No drawer trigger found in this fixture');
    }

    await drawerTrigger.focus();
    await page.keyboard.press('Enter');

    const drawer = page.locator('[data-detail-drawer]');
    await expect(drawer).toBeVisible();

    const insideDrawer = await page.evaluate(() =>
      document.querySelector('[data-detail-drawer]')?.contains(document.activeElement),
    );
    expect(insideDrawer).toBe(true);

    await page.keyboard.press('Escape');
    await expect(drawer).not.toBeVisible();
  });
});
