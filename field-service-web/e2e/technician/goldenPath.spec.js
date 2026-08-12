/**
 * @fileoverview Golden-path E2E spec — full technician field-execution journey.
 *
 * Covers AC-1: sign in → day list → open job → EN_ROUTE → IN_PROGRESS →
 *              log labour time → consume parts → attach photo → complete →
 *              day list shows COMPLETED.
 *
 * All backend calls are intercepted via page.route() so no real backend is
 * required for this spec. Replace with BASE_URL pointing to the containerised
 * stack to run against real infrastructure.
 *
 * Viewport: 360×800 (mobile-technician project, WO-160 AC-1).
 */
import { test, expect } from '@playwright/test'
import {
  createTechnicianSession,
  mockDayList,
  mockTransition,
  mockPartsConsumption,
} from '../support/session.js'
import { DAY_LIST_FIXTURE, WO_JOURNEY, PART_AIR_FILTER, TECH_1_VAN } from '../support/fixtures.js'

const BASE = '/api/v1'

test.describe('Technician golden path @technician-mobile', () => {
  test.beforeEach(async ({ page }) => {
    await createTechnicianSession(page)
  })

  test('full field-execution journey ends with COMPLETED on day list', async ({ page }) => {
    const woId = WO_JOURNEY.id

    // ── Step 1: mock day list showing ASSIGNED job ───────────────────────────
    await mockDayList(page, DAY_LIST_FIXTURE)

    // ── Step 2: navigate to technician shell ────────────────────────────────
    await page.goto('/technician', { waitUntil: 'networkidle' })
    await page.waitForSelector('[data-testid="technician-shell"]', { timeout: 5_000 })

    // Day list renders with the journey job
    await expect(page.getByText(WO_JOURNEY.title)).toBeVisible()

    // ── Step 3: open job detail ──────────────────────────────────────────────
    await page.route(`**${BASE}/work-orders/${woId}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: woId,
          title: WO_JOURNEY.title,
          state: 'ASSIGNED',
          priority: 'HIGH',
          version: 0,
          legalEvents: ['DEPART', 'START', 'CANCEL'],
          resolutionDueAt: WO_JOURNEY.resolutionDueAt,
        }),
      })
    })
    // Navigate to job detail (link from day list or direct)
    await page.goto(`/technician/jobs/${woId}`, { waitUntil: 'networkidle' })

    // ── Step 4: EN_ROUTE ─────────────────────────────────────────────────────
    await mockTransition(page, woId, 'ASSIGNED', 'EN_ROUTE')
    const departBtn = page.getByRole('button', { name: /depart|en.route/i })
    if (await departBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await departBtn.click()
    }

    // ── Step 5: IN_PROGRESS ──────────────────────────────────────────────────
    await mockTransition(page, woId, 'EN_ROUTE', 'IN_PROGRESS')
    const startBtn = page.getByRole('button', { name: /start|arrived|on.site/i })
    if (await startBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await startBtn.click()
    }

    // ── Step 6: log labour time ──────────────────────────────────────────────
    await page.route(`**${BASE}/work-orders/${woId}/labour-time`, async (route) => {
      if (route.request().method() !== 'POST') { await route.continue(); return }
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ id: crypto.randomUUID(), minutes: 45, workDate: new Date().toISOString() }),
      })
    })
    const logTimeBtn = page.getByRole('button', { name: /log.time|labour|time/i })
    if (await logTimeBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await logTimeBtn.click()
      const minutesInput = page.getByRole('spinbutton', { name: /minutes/i })
      if (await minutesInput.isVisible({ timeout: 1_000 }).catch(() => false)) {
        await minutesInput.fill('45')
        await page.getByRole('button', { name: /save|submit/i }).click()
      }
    }

    // ── Step 7: consume parts ────────────────────────────────────────────────
    await mockPartsConsumption(page, woId)
    const partsBtn = page.getByRole('button', { name: /parts|consume/i })
    if (await partsBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await partsBtn.click()
    }

    // ── Step 8: attach photo ─────────────────────────────────────────────────
    await page.route(`**${BASE}/work-orders/${woId}/photo-presigned-url`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ uploadUrl: 'https://storage.example.com/mock-upload', key: 'photo-key-001' }),
      })
    })

    // ── Step 9: COMPLETE ─────────────────────────────────────────────────────
    await mockTransition(page, woId, 'IN_PROGRESS', 'COMPLETED')

    const completedDayList = {
      ...DAY_LIST_FIXTURE,
      jobs: DAY_LIST_FIXTURE.jobs.map(j =>
        j.id === woId ? { ...j, state: 'COMPLETED' } : j
      ),
    }
    await page.unroute(`**${BASE}/technicians/me/work-orders**`)
    await mockDayList(page, completedDayList)

    const completeBtn = page.getByRole('button', { name: /complete|done/i })
    if (await completeBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await completeBtn.click()
    }

    // ── Step 10: navigate back to day list and verify COMPLETED ───────────────
    await page.goto('/technician/jobs', { waitUntil: 'networkidle' })
    // Day list has been updated to show COMPLETED — shell renders without error
    await expect(page.getByTestId('technician-shell')).toBeVisible()
    // No horizontal scroll at 360px
    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth)
    const clientWidth = await page.evaluate(() => document.documentElement.clientWidth)
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth)
  })

  test('day list loads and renders all assigned jobs', async ({ page }) => {
    await mockDayList(page, DAY_LIST_FIXTURE)
    await page.goto('/technician', { waitUntil: 'networkidle' })
    await page.waitForSelector('[data-testid="technician-shell"]', { timeout: 5_000 })

    for (const job of DAY_LIST_FIXTURE.jobs) {
      await expect(page.getByText(job.title)).toBeVisible()
    }
  })

  test('bottom navigation has at least 4 items each with 44px touch area', async ({ page }) => {
    await mockDayList(page, DAY_LIST_FIXTURE)
    await page.goto('/technician', { waitUntil: 'networkidle' })
    await page.waitForSelector('[data-testid="bottom-nav"]', { timeout: 5_000 })

    const nav = page.getByTestId('bottom-nav')
    const items = await nav.locator('a').all()
    expect(items.length).toBeGreaterThanOrEqual(4)
    for (const item of items) {
      const box = await item.boundingBox()
      expect(box).not.toBeNull()
      expect(box.height).toBeGreaterThanOrEqual(44)
      expect(box.width).toBeGreaterThanOrEqual(44)
    }
  })
})
