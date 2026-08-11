/**
 * Accessibility gate — axe-core via @axe-core/playwright.
 *
 * Runs every persona surface in BOTH appearances and fails on any critical
 * or serious axe violation. A violation present in only one appearance
 * fails the build (WCAG 2.1 AA must pass in both).
 *
 * Parameterised over:
 *   - persona: dispatcher, technician, operations, customer
 *   - appearance: light, dark
 */
import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

const PERSONAS = [
  { name: 'dispatcher', path: '/dispatch', density: 'dispatcher' },
  { name: 'technician', path: '/field/jobs', density: 'technician' },
  { name: 'operations', path: '/operations', density: 'operations' },
  { name: 'customer', path: '/portal/requests', density: 'customer' },
]

const APPEARANCES = ['light', 'dark']

for (const appearance of APPEARANCES) {
  for (const persona of PERSONAS) {
    test(`axe: ${persona.name} in ${appearance}`, async ({ page }) => {
      await page.goto(persona.path, { waitUntil: 'networkidle' })

      // Set appearance via data attribute on html element (matches AppearanceProvider)
      await page.evaluate((app) => {
        document.documentElement.setAttribute('data-appearance', app)
      }, appearance)

      // Wait for layout paint
      await page.waitForTimeout(200)

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
        .disableRules(['color-contrast']) // checked by contrast.test.js against token values
        .analyze()

      const criticalOrSerious = results.violations.filter(
        v => v.impact === 'critical' || v.impact === 'serious'
      )

      if (criticalOrSerious.length > 0) {
        const detail = criticalOrSerious.map(v =>
          `  [${v.impact.toUpperCase()}] ${v.id}: ${v.description}\n` +
          v.nodes.slice(0, 2).map(n => `    target: ${n.target}`).join('\n')
        ).join('\n')
        throw new Error(
          `${criticalOrSerious.length} axe violation(s) in ${persona.name}/${appearance}:\n${detail}`
        )
      }

      expect(criticalOrSerious).toHaveLength(0)
    })
  }
}
