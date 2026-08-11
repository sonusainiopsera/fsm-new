/**
 * Greyscale audit gate — Playwright.
 *
 * Applies a CSS greyscale filter and asserts every Chip, KPI delta,
 * risk indicator and chart series retains meaning through text label
 * plus a distinct icon or shape (BR-34).
 *
 * Runs in both appearances to catch appearance-specific regressions.
 */
import { test, expect } from '@playwright/test'

const APPEARANCES = ['light', 'dark']

const PAGES = [
  { name: 'dispatcher', path: '/dispatch' },
  { name: 'operations', path: '/operations' },
  { name: 'field', path: '/field/jobs' },
  { name: 'portal', path: '/portal/requests' },
]

for (const appearance of APPEARANCES) {
  for (const page_ of PAGES) {
    test(`greyscale: ${page_.name} in ${appearance}`, async ({ page }) => {
      await page.goto(page_.path, { waitUntil: 'networkidle' })

      // Apply greyscale filter and set appearance
      await page.evaluate((app) => {
        document.documentElement.setAttribute('data-appearance', app)
        // Apply greyscale via CSS to simulate colour-blind / greyscale display
        document.body.style.filter = 'grayscale(100%)'
      }, appearance)

      await page.waitForTimeout(200)

      // Assert every status chip has a text label AND an icon/shape element
      const chipResults = await page.evaluate(() => {
        const chips = Array.from(document.querySelectorAll('[data-kind]'))
        return chips.map(el => ({
          kind: el.getAttribute('data-kind'),
          value: el.getAttribute('data-value'),
          hasText: (el.textContent?.trim() ?? '').length > 0,
          hasIcon: el.querySelector('[aria-hidden="true"]') !== null
            || el.querySelector('svg') !== null
            || Array.from(el.querySelectorAll('span')).some(s => {
              const t = (s.textContent ?? '').trim()
              return t.length > 0 && t.length <= 3 && /[^\w\s]/.test(t)
            }),
        }))
      })

      const failures = chipResults.filter(r => !r.hasText || !r.hasIcon)

      if (failures.length > 0) {
        const detail = failures.map(f =>
          `  ${f.kind}/${f.value}: hasText=${f.hasText}, hasIcon=${f.hasIcon}`
        ).join('\n')
        throw new Error(
          `${failures.length} chip(s) failed greyscale audit on ${page_.name}/${appearance}:\n${detail}\n` +
          'Each status indicator must carry both text label and icon/shape (BR-34).'
        )
      }

      // Assert KPI delta indicators carry non-colour meaning
      const kpiResults = await page.evaluate(() => {
        const kpis = Array.from(document.querySelectorAll('[data-kpi-delta]'))
        return kpis.map(el => ({
          text: el.textContent?.trim() ?? '',
          hasDirectionSymbol: /[▲▼↑↓+\-]/.test(el.textContent ?? ''),
        }))
      })

      const kpiFailures = kpiResults.filter(r => r.text.length > 0 && !r.hasDirectionSymbol)
      if (kpiFailures.length > 0) {
        throw new Error(
          `${kpiFailures.length} KPI delta(s) on ${page_.name}/${appearance} lack a direction symbol (▲/▼/+/-). ` +
          'Direction must not rely on colour alone.'
        )
      }

      expect(failures).toHaveLength(0)
    })
  }
}
