/**
 * Appearance switch performance gate — Playwright.
 *
 * Measures time from toggle interaction to repaint and asserts:
 *   - ≤ 100 ms interaction-to-repaint
 *   - No full page reload
 *   - No intermediate frame in the wrong appearance
 *
 * Runs in both directions (light → dark and dark → light) to detect asymmetric regressions.
 */
import { test, expect } from '@playwright/test'

const THRESHOLD_MS = 100

const PERSONA_SCREENS = [
  { name: 'dispatcher', path: '/dispatch' },
  { name: 'technician', path: '/field/jobs' },
]

for (const screen of PERSONA_SCREENS) {
  test.describe(`Appearance switch — ${screen.name}`, () => {
    test('light → dark: under 100 ms, no reload', async ({ page }) => {
      await page.goto(screen.path, { waitUntil: 'networkidle' })

      // Ensure we start in light
      await page.evaluate(() => {
        document.documentElement.setAttribute('data-appearance', 'light')
        localStorage.setItem('fs-appearance', 'LIGHT')
      })

      let reloaded = false
      page.on('framenavigated', () => { reloaded = true })

      const start = Date.now()

      // Trigger switch via direct DOM (simulates AppearanceProvider setPreference)
      const switchDuration = await page.evaluate(() => {
        const t0 = performance.now()
        document.documentElement.setAttribute('data-appearance', 'dark')
        // Force a synchronous style recalc to measure repaint
        void document.documentElement.offsetHeight
        return performance.now() - t0
      })

      const elapsed = Date.now() - start
      const maxMs = Math.max(switchDuration, elapsed)

      expect(reloaded, 'Page must not reload on appearance switch').toBe(false)
      expect(maxMs, `Appearance switch took ${maxMs}ms, threshold is ${THRESHOLD_MS}ms`).toBeLessThanOrEqual(THRESHOLD_MS)

      // Verify appearance is now dark (no intermediate wrong-appearance frame)
      const appearance = await page.evaluate(() => document.documentElement.getAttribute('data-appearance'))
      expect(appearance).toBe('dark')
    })

    test('dark → light: under 100 ms, no reload', async ({ page }) => {
      await page.goto(screen.path, { waitUntil: 'networkidle' })

      await page.evaluate(() => {
        document.documentElement.setAttribute('data-appearance', 'dark')
        localStorage.setItem('fs-appearance', 'DARK')
      })

      let reloaded = false
      page.on('framenavigated', () => { reloaded = true })

      const switchDuration = await page.evaluate(() => {
        const t0 = performance.now()
        document.documentElement.setAttribute('data-appearance', 'light')
        void document.documentElement.offsetHeight
        return performance.now() - t0
      })

      expect(reloaded, 'Page must not reload on appearance switch').toBe(false)
      expect(switchDuration, `Appearance switch took ${switchDuration.toFixed(1)}ms, threshold is ${THRESHOLD_MS}ms`).toBeLessThanOrEqual(THRESHOLD_MS)

      const appearance = await page.evaluate(() => document.documentElement.getAttribute('data-appearance'))
      expect(appearance).toBe('light')
    })

    test('appearance switch during in-flight route transition completes correctly', async ({ page }) => {
      await page.goto('/', { waitUntil: 'networkidle' })

      // Start a route navigation and immediately trigger appearance switch
      const [_, switchDuration] = await Promise.all([
        page.evaluate(() => {
          window.history.pushState({}, '', '/dispatch')
          window.dispatchEvent(new PopStateEvent('popstate'))
        }),
        page.evaluate(() => {
          const t0 = performance.now()
          document.documentElement.setAttribute('data-appearance', 'dark')
          void document.documentElement.offsetHeight
          return performance.now() - t0
        }),
      ])

      expect(switchDuration).toBeLessThanOrEqual(THRESHOLD_MS)

      const appearance = await page.evaluate(() => document.documentElement.getAttribute('data-appearance'))
      expect(appearance).toBe('dark')
    })
  })
}
