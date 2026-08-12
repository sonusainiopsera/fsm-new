/**
 * @fileoverview React Testing Library tests for ReadinessReportPage (WO-122, AC-7).
 *
 * Uses MSW-style fetch stub via vi.stubGlobal to isolate from real HTTP.
 * Covers: KPI widget rendering, gate verdict, empty drill-down state,
 * and axe accessibility checks in both light and dark appearances.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import ReadinessReportPage from '../ReadinessReportPage.jsx'
import { createReadinessHandlers } from '../../../../mocks/handlers/readiness.js'
import summaryFixture   from '../../../../mocks/fixtures/readiness-summary.json'
import gapsFixture      from '../../../../mocks/fixtures/readiness-gaps.json'
import snapshotsFixture from '../../../../mocks/fixtures/readiness-snapshots.json'

// axe for accessibility checks
import { axe } from 'axe-core'

function makeQueryClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
}

function Wrapper({ children }) {
  return (
    <QueryClientProvider client={makeQueryClient()}>
      {children}
    </QueryClientProvider>
  )
}

function stubFetch(handler) {
  vi.stubGlobal('fetch', (url, opts) => {
    const result = handler(url, opts)
    if (result) return result
    return Promise.resolve({
      ok: false, status: 404,
      headers: { get: () => null },
      json: () => Promise.resolve({ code: 'NOT_FOUND' }),
      text: () => Promise.resolve('not found'),
    })
  })
}

beforeEach(() => {
  vi.restoreAllMocks()
})

// ── KPI widget rendering ──────────────────────────────────────────────────────

describe('ReadinessReportPage — KPI widgets', () => {
  it('renders readiness percentage from summary', async () => {
    stubFetch(createReadinessHandlers({ role: 'ADMIN', summary: summaryFixture, gaps: gapsFixture, snapshots: snapshotsFixture }))
    render(<ReadinessReportPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText(/40.00%/i))
    expect(screen.getByText(/40.00%/i)).toBeInTheDocument()
  })

  it('renders gate verdict as NOT met', async () => {
    stubFetch(createReadinessHandlers({ role: 'ADMIN', summary: summaryFixture }))
    render(<ReadinessReportPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText(/Gate not met/i))
    expect(screen.getByText(/Gate not met/i)).toBeInTheDocument()
  })

  it('renders blocking technician count', async () => {
    stubFetch(createReadinessHandlers({ role: 'ADMIN', summary: summaryFixture }))
    render(<ReadinessReportPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText(/Blocking technicians/i))
    // blockingTechnicianCount = 3 from fixture
    expect(screen.getByText('3')).toBeInTheDocument()
  })

  it('renders N/A when not applicable', async () => {
    const notApplicable = { ...summaryFixture, applicable: false, readinessPercent: null }
    stubFetch(createReadinessHandlers({ role: 'ADMIN', summary: notApplicable }))
    render(<ReadinessReportPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getAllByText(/N\/A/i))
    expect(screen.getAllByText(/N\/A/i).length).toBeGreaterThan(0)
  })
})

// ── Gate met — empty state ────────────────────────────────────────────────────

describe('ReadinessReportPage — gate met state', () => {
  it('shows satisfied state when gate is met', async () => {
    const metSummary = { ...summaryFixture, gateMet: true, blockingTechnicianCount: 0, readinessPercent: 100 }
    stubFetch(createReadinessHandlers({ role: 'ADMIN', summary: metSummary }))
    render(<ReadinessReportPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText(/Gate met/i))
    expect(screen.getByText(/all active technicians are compliant/i)).toBeInTheDocument()
  })
})

// ── Drill-down table ──────────────────────────────────────────────────────────

describe('ReadinessReportPage — gap drill-down', () => {
  it('renders gap table with technician names', async () => {
    stubFetch(createReadinessHandlers({ role: 'ADMIN', summary: summaryFixture, gaps: gapsFixture }))
    render(<ReadinessReportPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText(/missing cert/i))
    expect(screen.getByText(/Readiness Tech 2/i)).toBeInTheDocument()
  })
})

// ── Accessibility ─────────────────────────────────────────────────────────────

describe('ReadinessReportPage — accessibility', () => {
  it('has no axe violations in light appearance', async () => {
    stubFetch(createReadinessHandlers({ role: 'ADMIN', summary: summaryFixture, gaps: gapsFixture, snapshots: snapshotsFixture }))
    const { container } = render(<ReadinessReportPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText(/40.00%/i))
    document.documentElement.setAttribute('data-appearance', 'light')
    const results = await axe(container)
    expect(results.violations).toEqual([])
    document.documentElement.removeAttribute('data-appearance')
  })

  it('has no axe violations in dark appearance', async () => {
    stubFetch(createReadinessHandlers({ role: 'ADMIN', summary: summaryFixture, gaps: gapsFixture, snapshots: snapshotsFixture }))
    const { container } = render(<ReadinessReportPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText(/40.00%/i))
    document.documentElement.setAttribute('data-appearance', 'dark')
    const results = await axe(container)
    expect(results.violations).toEqual([])
    document.documentElement.removeAttribute('data-appearance')
  })
})
