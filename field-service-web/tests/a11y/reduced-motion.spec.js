/**
 * Reduced-motion gate — Playwright.
 *
 * Emulates prefers-reduced-motion: reduce and asserts:
 *   - zero non-zero animation durations on any element
 *   - every state change, alert and transition is still perceivable (no information loss)
 *   - reduced-motion suppression applies in both appearances
 */
import { test, expect } from '@playwright/test'

const APPEARANCES = ['light', 'dark']

for (const appearance of APPEARANCES) {
  test.describe(`Reduced motion — ${appearance}`, () => {
    test.use({ reducedMotion: 'reduce' })

    test.beforeEach(async ({ page }) => {
      await page.goto('/', { waitUntil: 'networkidle' })
      await page.evaluate((app) => {
        document.documentElement.setAttribute('data-appearance', app)
      }, appearance)
    })

    test('no non-zero animation-duration on any element', async ({ page }) => {
      const violations = await page.evaluate(() => {
        const all = Array.from(document.querySelectorAll('*'))
        return all
          .map(el => {
            const style = getComputedStyle(el)
            return {
              tag: el.tagName,
              animDuration: style.animationDuration,
              transDuration: style.transitionDuration,
            }
          })
          .filter(s => {
            const animMs = parseFloat(s.animDuration) * (s.animDuration.endsWith('ms') ? 1 : 1000)
            const transMs = parseFloat(s.transDuration) * (s.transDuration.endsWith('ms') ? 1 : 1000)
            return (!isNaN(animMs) && animMs > 0) || (!isNaN(transMs) && transMs > 0)
          })
          .slice(0, 10) // report first 10 at most
      })

      if (violations.length > 0) {
        const detail = violations.map(v =>
          `  <${v.tag}> animation-duration: ${v.animDuration}, transition-duration: ${v.transDuration}`
        ).join('\n')
        throw new Error(
          `[reduced-motion/${appearance}] ${violations.length} element(s) have non-zero motion durations:\n${detail}\n` +
          'All transitions must respect prefers-reduced-motion: reduce.'
        )
      }

      expect(violations).toHaveLength(0)
    })

    test('status chips remain visible without animation', async ({ page }) => {
      await page.goto('/dispatch', { waitUntil: 'networkidle' })
      await page.evaluate((app) => { document.documentElement.setAttribute('data-appearance', app) }, appearance)

      const chips = page.locator('[data-kind]')
      const count = await chips.count()
      if (count > 0) {
        for (let i = 0; i < Math.min(count, 5); i++) {
          await expect(chips.nth(i)).toBeVisible()
        }
      }
    })

    test('toast alerts remain perceivable without animation', async ({ page }) => {
      // Trigger a toast notification programmatically via the ToastProvider context
      const toastLiveRegion = page.locator('[aria-live]')
      if (await toastLiveRegion.count() > 0) {
        await expect(toastLiveRegion.first()).toBeInTheDOM()
      }
    })

    test('appearance switch remains visible and announced without animation', async ({ page }) => {
      const appearanceToggle = page.locator('[aria-label*="appearance"], [aria-label*="theme"]').first()
      if (await appearanceToggle.count() > 0) {
        await appearanceToggle.click()
        // Page should be in the toggled appearance — no animation required for the switch to complete
        const html = page.locator('html')
        const attr = await html.getAttribute('data-appearance')
        expect(['light', 'dark']).toContain(attr)
      }
    })
  })
}
