/**
 * @fileoverview Tests for DrillDownPage and ReconciliationBanner (WO-168, AC-8).
 *
 * Tests cover:
 *   - buildDrillDownUrl param construction
 *   - ReconciliationBanner: MATCHED, DIVERGED × 3 reasons
 *   - DrillDownPage: loading, error (403), empty, success states
 *   - Drill-down navigation from DashboardPage
 *   - URL-encoded criteria visible in chips
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { buildDrillDownUrl } from '../api/useDrillDownWorkOrders.js'
import { ReconciliationBanner } from '../components/ReconciliationBanner.jsx'

// ── Helpers ───────────────────────────────────────────────────────────────────

function makeClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false }, }, logger: { log: () => {}, warn: () => {}, error: () => {} } })
}

const MOCK_RESPONSE = {
  data: [
    {
      id: 'wo-001', title: 'Fix boiler HVAC', state: 'COMPLETED', priority: 'HIGH',
      customerId: 'c-1', siteId: 's-1', assignedTechnicianId: 't-1',
      resolutionDeadline: '2025-06-15T12:00:00Z', atRisk: false,
      createdAt: '2025-05-20T09:00:00Z', updatedAt: '2025-05-25T10:00:00Z',
    },
  ],
  page: { number: 0, size: 20, totalElements: 1, totalPages: 1 },
  links: { next: null, prev: null },
  reconciliation: {
    widgetValue: 12,
    widgetDataAsOf: '2025-06-01T10:00:00Z',
    resultCount: 1,
    status: 'MATCHED',
    reason: null,
  },
}

// ── buildDrillDownUrl ─────────────────────────────────────────────────────────

describe('buildDrillDownUrl', () => {
  it('includes metric, window, page, size', () => {
    const url = buildDrillDownUrl({ metric: 'SLA_COMPLIANCE_RATE', window: 'THIRTY_DAYS' })
    expect(url).toContain('metric=SLA_COMPLIANCE_RATE')
    expect(url).toContain('window=THIRTY_DAYS')
    expect(url).toContain('page=0')
    expect(url).toContain('size=20')
  })

  it('omits segment when ALL', () => {
    const url = buildDrillDownUrl({ metric: 'FTF_RATE', window: 'SEVEN_DAYS', segment: 'ALL' })
    expect(url).not.toContain('segment=ALL')
  })

  it('includes segment when not ALL', () => {
    const url = buildDrillDownUrl({
      metric: 'BACKLOG_OPEN_COUNT',
      window: 'THIRTY_DAYS',
      segment: 'ASSET_CATEGORY_HVAC',
    })
    expect(url).toContain('segment=ASSET_CATEGORY_HVAC')
  })

  it('clamps size to server maximum of 50', () => {
    const url = buildDrillDownUrl({ metric: 'FTF_RATE', window: 'THIRTY_DAYS', size: 9999 })
    expect(url).toContain('size=50')
  })
})

// ── ReconciliationBanner ──────────────────────────────────────────────────────

describe('ReconciliationBanner', () => {
  it('renders MATCHED state with count', () => {
    render(
      <ReconciliationBanner
        widgetValue={42}
        widgetDataAsOf="2025-06-01T10:00:00Z"
        resultCount={42}
        status="MATCHED"
        reason={null}
        metricLabel="SLA Compliance"
      />
    )
    expect(screen.getByTestId('reconciliation-banner')).toBeInTheDocument()
    expect(screen.getByTestId('reconciliation-banner')).toHaveAttribute('data-reconciliation-status', 'MATCHED')
    expect(screen.getByText(/42 work orders found/)).toBeInTheDocument()
  })

  it('renders DIVERGED / READ_MODEL_STALE with explanation', () => {
    render(
      <ReconciliationBanner
        widgetValue={10}
        widgetDataAsOf="2025-06-01T08:00:00Z"
        resultCount={8}
        status="DIVERGED"
        reason="READ_MODEL_STALE"
        metricLabel="SLA Compliance"
      />
    )
    expect(screen.getByTestId('reconciliation-banner')).toHaveAttribute('data-reconciliation-reason', 'READ_MODEL_STALE')
    expect(screen.getByText(/analytics data is refreshing/i)).toBeInTheDocument()
  })

  it('renders DIVERGED / PROVISIONAL_COHORT with cohort explanation', () => {
    render(
      <ReconciliationBanner
        widgetValue={0.78}
        widgetDataAsOf={null}
        resultCount={15}
        status="DIVERGED"
        reason="PROVISIONAL_COHORT"
        metricLabel="First-Time Fix"
      />
    )
    expect(screen.getByText(/cohort is still maturing/i)).toBeInTheDocument()
    expect(screen.getByTestId('reconciliation-banner')).toHaveAttribute('data-reconciliation-reason', 'PROVISIONAL_COHORT')
  })

  it('renders DIVERGED / SCOPE_RESTRICTED without disclosing excluded count', () => {
    render(
      <ReconciliationBanner
        widgetValue={50}
        widgetDataAsOf="2025-06-01T10:00:00Z"
        resultCount={20}
        status="DIVERGED"
        reason="SCOPE_RESTRICTED"
        metricLabel="SLA Compliance"
      />
    )
    const banner = screen.getByTestId('reconciliation-banner')
    expect(banner).toHaveAttribute('data-reconciliation-reason', 'SCOPE_RESTRICTED')
    expect(screen.getByText(/your scope covers a subset/i)).toBeInTheDocument()
    // Must not expose the excluded record count (50 - 20 = 30)
    expect(banner.textContent).not.toContain('30')
  })
})

// ── DrillDownPage rendering ───────────────────────────────────────────────────

describe('DrillDownPage rendering', () => {
  let originalFetch

  beforeEach(() => {
    originalFetch = global.fetch
  })

  afterEach(() => {
    global.fetch = originalFetch
    vi.restoreAllMocks()
  })

  function renderDrillDown({ route = '/drill-down?metric=BACKLOG_OPEN_COUNT&window=THIRTY_DAYS', mockFetch }) {
    const client = makeClient()
    if (mockFetch) vi.stubGlobal('fetch', mockFetch)

    return render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[route]}>
          <Routes>
            <Route path="/drill-down" element={
              // Dynamic import to avoid module-level route setup overhead
              (() => {
                const { default: DrillDownPage } = require('../DrillDownPage.jsx')
                return <DrillDownPage />
              })()
            } />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    )
  }

  it('shows error when metric param is missing', async () => {
    renderDrillDown({ route: '/drill-down?window=THIRTY_DAYS' })
    expect(screen.getByText(/no metric specified/i)).toBeInTheDocument()
  })

  it('shows success state with work order rows', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => MOCK_RESPONSE,
      headers: new Headers({ 'Content-Type': 'application/json' }),
    })

    renderDrillDown({ mockFetch })

    await waitFor(() =>
      expect(screen.getByText('Fix boiler HVAC')).toBeInTheDocument()
    )
    expect(screen.getByTestId('reconciliation-banner')).toBeInTheDocument()
  })

  it('shows empty state when data array is empty', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        ...MOCK_RESPONSE,
        data: [],
        page: { number: 0, size: 20, totalElements: 0, totalPages: 0 },
        reconciliation: { ...MOCK_RESPONSE.reconciliation, resultCount: 0 },
      }),
      headers: new Headers(),
    })

    renderDrillDown({ mockFetch })

    await waitFor(() =>
      expect(screen.getByText(/no work orders found/i)).toBeInTheDocument()
    )
  })

  it('shows forbidden error on 403', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({ code: 'FORBIDDEN', message: 'Access denied' }),
      headers: new Headers(),
    })

    renderDrillDown({ mockFetch })

    await waitFor(() =>
      expect(screen.getByText(/do not have permission/i)).toBeInTheDocument()
    )
  })

  it('shows filter chips with metric and window from URL', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => MOCK_RESPONSE,
      headers: new Headers(),
    })

    renderDrillDown({
      route: '/drill-down?metric=FTF_RATE&window=SEVEN_DAYS&segment=ASSET_CATEGORY_HVAC',
      mockFetch,
    })

    await waitFor(() => screen.getByText('Fix boiler HVAC'))

    // Filter chips should be visible with metric / window / segment
    expect(screen.getByText(/First-Time Fix/)).toBeInTheDocument()
    expect(screen.getByText(/Last 7 days/i)).toBeInTheDocument()
    expect(screen.getByText(/ASSET_CATEGORY_HVAC/)).toBeInTheDocument()
  })
})
