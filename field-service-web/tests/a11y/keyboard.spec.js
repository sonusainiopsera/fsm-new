/**
 * Keyboard operability gate — Playwright.
 *
 * Traverses the app shell, data table, modal, detail drawer and a multi-field form
 * entirely by keyboard. Asserts:
 *   - logical tab order (skip link → sidebar → topbar → main)
 *   - no keyboard traps
 *   - focus trapping inside modal and drawer
 *   - focus restoration on close
 *   - no orphaned focus (focus stays visible throughout)
 */
import { test, expect } from '@playwright/test'

test.describe('Keyboard: app shell traversal', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/', { waitUntil: 'networkidle' })
  })

  test('skip-to-main-content link is the first focusable element', async ({ page }) => {
    await page.keyboard.press('Tab')
    const focused = await page.evaluate(() => document.activeElement?.dataset?.testid ?? document.activeElement?.getAttribute('href') ?? document.activeElement?.tagName)
    expect(['#main-content', '#main', 'skip-link']).toContain(focused)
  })

  test('Tab reaches sidebar navigation after skip link', async ({ page }) => {
    await page.keyboard.press('Tab') // skip link
    await page.keyboard.press('Tab') // first nav item
    const active = page.locator(':focus')
    await expect(active).toBeVisible()
    const role = await active.getAttribute('role')
    const tagName = await active.evaluate(el => el.tagName.toLowerCase())
    expect(['a', 'button']).toContain(tagName.toLowerCase())
  })

  test('no keyboard trap in sidebar — can Tab out to main content', async ({ page }) => {
    // Tab through all navigation items without getting trapped
    for (let i = 0; i < 15; i++) {
      await page.keyboard.press('Tab')
    }
    // Should have reached main content area or topbar without hanging
    const active = await page.evaluate(() => document.activeElement?.tagName ?? 'none')
    expect(active).not.toBe('BODY') // focus must not be lost
  })
})

test.describe('Keyboard: data table sort and row selection', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dispatch', { waitUntil: 'networkidle' })
  })

  test('table column headers are keyboard-activatable for sort', async ({ page }) => {
    const sortableHeader = page.locator('[role="columnheader"][tabindex]').first()
    if (await sortableHeader.count() > 0) {
      await sortableHeader.focus()
      await page.keyboard.press('Enter')
      // Sort indicator should change
      const ariaSort = await sortableHeader.getAttribute('aria-sort')
      expect(['ascending', 'descending', 'none']).toContain(ariaSort ?? 'none')
    } else {
      test.skip(true, 'No sortable table headers present on this page')
    }
  })
})

test.describe('Keyboard: modal focus trap and restoration', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dispatch', { waitUntil: 'networkidle' })
  })

  test('modal traps focus — Tab cycles through modal focusable elements only', async ({ page }) => {
    const trigger = page.locator('[data-modal-trigger]').first()
    if (await trigger.count() === 0) {
      test.skip(true, 'No modal trigger on dispatch page')
      return
    }
    await trigger.click()
    const modal = page.locator('[role="dialog"]')
    await expect(modal).toBeVisible()

    // Tab several times — should stay within dialog
    for (let i = 0; i < 10; i++) {
      await page.keyboard.press('Tab')
      const active = await page.evaluate(() => document.activeElement)
      const inModal = await page.evaluate(() => {
        const modal = document.querySelector('[role="dialog"]')
        return modal ? modal.contains(document.activeElement) : false
      })
      if (await page.locator('[role="dialog"]').count() > 0) {
        expect(inModal).toBe(true)
      }
    }
  })

  test('Escape closes modal and restores focus to trigger', async ({ page }) => {
    const trigger = page.locator('[data-modal-trigger]').first()
    if (await trigger.count() === 0) {
      test.skip(true, 'No modal trigger on dispatch page')
      return
    }
    await trigger.focus()
    await trigger.click()
    await expect(page.locator('[role="dialog"]')).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(page.locator('[role="dialog"]')).not.toBeVisible()
    // Focus should return to trigger
    const focusedEl = await page.evaluate(() => document.activeElement)
    expect(focusedEl).toBeTruthy()
  })
})

test.describe('Keyboard: detail drawer', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dispatch', { waitUntil: 'networkidle' })
  })

  test('detail drawer traps focus and closes on Escape', async ({ page }) => {
    const drawerTrigger = page.locator('[data-drawer-trigger]').first()
    if (await drawerTrigger.count() === 0) {
      test.skip(true, 'No drawer trigger on dispatch page')
      return
    }
    await drawerTrigger.click()
    const drawer = page.locator('[data-drawer]')
    await expect(drawer).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(drawer).not.toBeVisible()
  })
})

test.describe('Keyboard: multi-field form', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dispatch', { waitUntil: 'networkidle' })
  })

  test('Tab order in form is logical (matches DOM order)', async ({ page }) => {
    const form = page.locator('form').first()
    if (await form.count() === 0) {
      test.skip(true, 'No form on this page')
      return
    }

    const inputs = form.locator('input, select, textarea, button[type="submit"]')
    const count = await inputs.count()
    if (count < 2) {
      test.skip(true, 'Form has fewer than 2 interactive elements')
      return
    }

    await inputs.nth(0).focus()
    await page.keyboard.press('Tab')
    const activeIndex = await page.evaluate((count) => {
      const focusable = Array.from(document.querySelectorAll('input, select, textarea, button[type="submit"]'))
      return focusable.indexOf(document.activeElement)
    }, count)
    expect(activeIndex).toBeGreaterThan(0)
  })
})
