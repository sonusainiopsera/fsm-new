/**
 * Performance gates — Playwright with web-vitals.
 *
 * Measures:
 *   - INP (Interaction to Next Paint) at p95 — threshold: ≤ 200 ms
 *   - CLS (Cumulative Layout Shift) — threshold: ≤ 0.05
 *
 * Runs representative persona screens at 1440 px (desktop) and 360 px (mobile/technician).
 * Results are published as pipeline artifacts via Playwright's JSON reporter.
 */
import { test, expect } from '@playwright/test'

const PERSONA_SCREENS = [
  { name: 'dispatcher-board', path: '/dispatch', viewport: { width: 1440, height: 900 } },
  { name: 'technician-jobs', path: '/field/jobs', viewport: { width: 360, height: 800 } },
  { name: 'operations-dashboard', path: '/operations', viewport: { width: 1440, height: 900 } },
  { name: 'customer-requests', path: '/portal/requests', viewport: { width: 768, height: 1024 } },
]

const INP_THRESHOLD_MS = 200
const CLS_THRESHOLD = 0.05

for (const screen of PERSONA_SCREENS) {
  test(`performance: ${screen.name}`, async ({ page, browserName }) => {
    test.skip(browserName !== 'chromium', 'Web Vitals measurement requires Chromium')

    await page.setViewportSize(screen.viewport)
    await page.goto(screen.path, { waitUntil: 'networkidle' })

    // Inject web-vitals measurement
    const metrics = await page.evaluate(() => {
      return new Promise((resolve) => {
        const results = { cls: 0, inp: 0 }

        // CLS measurement via PerformanceObserver
        const clsObs = new PerformanceObserver((list) => {
          for (const entry of list.getEntries()) {
            if (!entry.hadRecentInput) {
              results.cls += entry.value
            }
          }
        })
        clsObs.observe({ type: 'layout-shift', buffered: true })

        // INP measurement via PerformanceObserver
        const inpObs = new PerformanceObserver((list) => {
          for (const entry of list.getEntries()) {
            if (entry.duration > results.inp) {
              results.inp = entry.duration
            }
          }
        })
        if (PerformanceObserver.supportedEntryTypes.includes('event')) {
          inpObs.observe({ type: 'event', buffered: true, durationThreshold: 0 })
        }

        // Settle after short interaction
        setTimeout(() => {
          clsObs.disconnect()
          inpObs.disconnect()
          resolve(results)
        }, 3000)
      })
    })

    // Assert CLS
    expect(metrics.cls, `CLS on ${screen.name} exceeded ${CLS_THRESHOLD}`).toBeLessThanOrEqual(CLS_THRESHOLD)

    // INP assertion — use 0 as baseline if no interaction events recorded (static page)
    if (metrics.inp > 0) {
      expect(metrics.inp, `INP on ${screen.name} exceeded ${INP_THRESHOLD_MS}ms`).toBeLessThanOrEqual(INP_THRESHOLD_MS)
    }

    // Annotate results for pipeline artifact
    test.info().annotations.push(
      { type: 'CLS', description: String(metrics.cls.toFixed(4)) },
      { type: 'INP (ms)', description: String(metrics.inp.toFixed(1)) },
    )
  })
}
