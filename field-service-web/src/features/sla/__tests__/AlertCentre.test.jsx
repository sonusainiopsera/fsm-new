import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { AlertCentre } from '../AlertCentre.jsx'

// ── Helpers ───────────────────────────────────────────────────────────────────

function wrapper(qc) {
  return function Wrapper({ children }) {
    return (
      <QueryClientProvider client={qc}>
        <MemoryRouter>{children}</MemoryRouter>
      </QueryClientProvider>
    )
  }
}

function makeQueryClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false } },
    logger: { log: () => {}, warn: () => {}, error: () => {} },
  })
}

const ALERTS_FIXTURE = {
  alerts: [
    {
      workOrderId: 'wo-001',
      reference: 'REF-001',
      title: 'HVAC Repair',
      riskLevel: 'breached',
      minutesRemaining: -15,
      state: 'ASSIGNED',
      triggerReason: 'Response window elapsed',
      projectionBasis: 'SLA policy P1',
    },
    {
      workOrderId: 'wo-002',
      reference: 'REF-002',
      title: 'Generator Fault',
      riskLevel: 'at_risk',
      minutesRemaining: 25,
      state: 'NEW',
      triggerReason: 'No technician assigned',
      projectionBasis: 'Predictive model',
    },
    {
      workOrderId: 'wo-003',
      reference: 'REF-003',
      title: 'Electrical Panel',
      riskLevel: 'at_risk',
      minutesRemaining: 42,
      state: 'IN_PROGRESS',
      triggerReason: 'Duration exceeding estimate',
      projectionBasis: 'Historical rate',
    },
  ],
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('AlertCentre', () => {
  let fetchMock

  beforeEach(() => {
    fetchMock = vi.fn((url) => {
      if (url.includes('/sla-alerts/open')) {
        return Promise.resolve({
          ok: true,
          status: 200,
          headers: { get: () => null },
          json: () => Promise.resolve(ALERTS_FIXTURE),
        })
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) })
    })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders a list of open alerts', async () => {
    const qc = makeQueryClient()
    render(
      <AlertCentre streamStatus="live" onRefresh={vi.fn()} />,
      { wrapper: wrapper(qc) }
    )

    await waitFor(() => {
      expect(screen.getByText('HVAC Repair')).toBeTruthy()
      expect(screen.getByText('Generator Fault')).toBeTruthy()
      expect(screen.getByText('Electrical Panel')).toBeTruthy()
    })
  })

  it('renders breached item before at_risk items (urgency sort)', async () => {
    const qc = makeQueryClient()
    render(
      <AlertCentre streamStatus="live" onRefresh={vi.fn()} />,
      { wrapper: wrapper(qc) }
    )

    await waitFor(() => {
      expect(screen.getByText('HVAC Repair')).toBeTruthy()
    })

    const items = screen.getAllByRole('listitem')
    const texts = items.map(li => li.textContent)
    const breachedIdx = texts.findIndex(t => t.includes('HVAC Repair'))
    const atRisk25Idx = texts.findIndex(t => t.includes('Generator Fault'))
    const atRisk42Idx = texts.findIndex(t => t.includes('Electrical Panel'))

    // Breached first
    expect(breachedIdx).toBeLessThan(atRisk25Idx)
    // Among at_risk, least time remaining first
    expect(atRisk25Idx).toBeLessThan(atRisk42Idx)
  })

  it('shows empty state when there are no alerts', async () => {
    fetchMock.mockImplementation((url) => {
      if (url.includes('/sla-alerts/open')) {
        return Promise.resolve({
          ok: true,
          status: 200,
          headers: { get: () => null },
          json: () => Promise.resolve({ alerts: [] }),
        })
      }
      return Promise.resolve({ ok: true, json: () => Promise.resolve({}) })
    })

    const qc = makeQueryClient()
    render(
      <AlertCentre streamStatus="live" onRefresh={vi.fn()} />,
      { wrapper: wrapper(qc) }
    )

    await waitFor(() => {
      // Should show some empty-state message
      expect(document.body.textContent).toMatch(/no.*alert|all.*clear|no.*at.risk/i)
    })
  })

  it('shows stale banner and refresh button when stream is stale', async () => {
    const onRefresh = vi.fn()
    const qc = makeQueryClient()
    render(
      <AlertCentre streamStatus="stale" onRefresh={onRefresh} />,
      { wrapper: wrapper(qc) }
    )

    await waitFor(() => {
      expect(screen.getByText(/HVAC Repair/)).toBeTruthy()
    })

    // Stale banner should be visible
    const banner = document.body.textContent
    expect(banner).toMatch(/stale|may be out.of.date|reconnect/i)

    // Refresh button should be available
    const refreshBtn = screen.queryByRole('button', { name: /refresh/i })
    expect(refreshBtn).toBeTruthy()
  })

  it('calls onRefresh when refresh button is clicked', async () => {
    const onRefresh = vi.fn()
    const qc = makeQueryClient()
    render(
      <AlertCentre streamStatus="stale" onRefresh={onRefresh} />,
      { wrapper: wrapper(qc) }
    )

    await waitFor(() => {
      expect(screen.getByText('HVAC Repair')).toBeTruthy()
    })

    const refreshBtn = screen.queryByRole('button', { name: /refresh/i })
    if (refreshBtn) {
      fireEvent.click(refreshBtn)
      expect(onRefresh).toHaveBeenCalledOnce()
    }
  })

  it('does not show stale banner when stream is live', async () => {
    const qc = makeQueryClient()
    render(
      <AlertCentre streamStatus="live" onRefresh={vi.fn()} />,
      { wrapper: wrapper(qc) }
    )

    await waitFor(() => {
      expect(screen.getByText('HVAC Repair')).toBeTruthy()
    })

    // No stale-specific text
    expect(document.body.textContent).not.toMatch(/stream.*stale|data.*out.of.date/i)
  })
})
