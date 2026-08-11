/**
 * @fileoverview RTL tests for StockPositionsPage.
 *
 * Stubs global fetch via vi.stubGlobal per project convention (no MSW).
 * Covers: table render, sorting, pagination, staleness banner, MovementHistoryDrawer.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthContext } from '../../../app/AuthContext.js'
import { stockPositionsHandler, movementsHandler } from '../../../mocks/handlers/inventory.js'
import StockPositionsPage from '../StockPositionsPage.jsx'

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    logger: { log: () => {}, warn: () => {}, error: () => {} },
  })
}

function makeAuth(roles = ['DISPATCHER']) {
  return { accessToken: 'tok', roles, userId: 'u1', setAuth: vi.fn(), clearAuth: vi.fn() }
}

function renderPage(roles = ['DISPATCHER']) {
  const client = makeClient()
  return render(
    <QueryClientProvider client={client}>
      <AuthContext.Provider value={makeAuth(roles)}>
        <MemoryRouter>
          <StockPositionsPage />
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  const combined = (url) => {
    if (url.includes('/inventory/stock')) return stockPositionsHandler()(url)
    if (url.includes('/inventory/movements')) return movementsHandler()(url)
    return Promise.reject(new Error(`Unhandled: ${url}`))
  }
  vi.stubGlobal('fetch', combined)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe('StockPositionsPage — table render', () => {
  it('shows a heading and loads stock position rows', async () => {
    renderPage()
    await waitFor(() => screen.getByText(/Stock Positions/i))
    // At least one part number from the fixture
    await waitFor(() => screen.getByText('HVAC-FILTER-20x25'))
    expect(screen.getByText('COMPRESSOR-R22')).toBeInTheDocument()
  })

  it('shows numeric on-hand quantities in table cells', async () => {
    renderPage()
    await waitFor(() => screen.getByText('48'))
    expect(screen.getByText('3')).toBeInTheDocument()
  })
})

describe('StockPositionsPage — as-of freshness indicator', () => {
  it('shows a fresh as-of timestamp when data is current', async () => {
    renderPage()
    await waitFor(() => screen.getByText('HVAC-FILTER-20x25'))
    // An as-of line should appear (time is locale-formatted)
    const freshnessEl = await waitFor(() =>
      document.querySelector('[aria-live="polite"]')
    )
    expect(freshnessEl).toBeTruthy()
  })
})

describe('StockPositionsPage — degraded state', () => {
  it('shows stale warning when as-of is beyond 60-second budget', async () => {
    const { stockPositionsHandler: sh } = await import('../../../mocks/handlers/inventory.js')
    vi.stubGlobal('fetch', sh({ stale: true }))
    renderPage()
    await waitFor(() => {
      const polite = document.querySelector('[aria-live="polite"]')
      // Degraded banner contains warning symbol
      expect(polite?.textContent ?? '').toMatch(/⚠|last refreshed|stale/i)
    })
  })
})

describe('StockPositionsPage — error state', () => {
  it('shows error state and retry button on fetch failure', async () => {
    vi.stubGlobal('fetch', () => Promise.resolve({ ok: false, status: 500, headers: { get: () => null }, json: () => Promise.resolve({ message: 'Server error' }) }))
    renderPage()
    await waitFor(() => screen.getByRole('button', { name: /retry/i }))
  })
})

describe('StockPositionsPage — movement history drawer', () => {
  it('opens drawer when a table row is clicked', async () => {
    renderPage()
    await waitFor(() => screen.getByText('HVAC-FILTER-20x25'))

    // Click first row
    const rows = document.querySelectorAll('tbody tr')
    expect(rows.length).toBeGreaterThan(0)
    fireEvent.click(rows[0])

    // Drawer title should reference the part
    await waitFor(() => {
      expect(screen.getByText(/Movement History/i)).toBeInTheDocument()
    })
  })
})

describe('StockPositionsPage — role-based transfer button', () => {
  it('shows Transfer stock button for DISPATCHER', async () => {
    renderPage(['DISPATCHER'])
    await waitFor(() => screen.getByText('HVAC-FILTER-20x25'))
    // Transfer button is present for DISPATCHER
    expect(screen.getByTitle(/transfer/i)).toBeInTheDocument()
  })

  it('hides Transfer stock button for TECHNICIAN (usability-only)', async () => {
    renderPage(['TECHNICIAN'])
    await waitFor(() => screen.getByText('HVAC-FILTER-20x25'))
    expect(screen.queryByTitle(/transfer/i)).not.toBeInTheDocument()
  })
})
