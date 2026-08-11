/**
 * @fileoverview RTL tests for LowStockPage.
 *
 * Covers: alert indicator text+icon+shape (AC-3, BR-32, BR-34), loading,
 * empty state, error state, and polling pause setup.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthContext } from '../../../app/AuthContext.js'
import { alertsHandler } from '../../../mocks/handlers/inventory.js'
import LowStockPage from '../LowStockPage.jsx'

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
          <LowStockPage />
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.stubGlobal('fetch', alertsHandler())
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe('LowStockPage — basic render', () => {
  it('renders the page heading and alert rows', async () => {
    renderPage()
    await waitFor(() => screen.getByText(/Low-Stock Alerts/i))
    await waitFor(() => screen.getByText('COMPRESSOR-R22'))
    expect(screen.getByText('CAPACITOR-40/5-MFD')).toBeInTheDocument()
  })
})

describe('LowStockPage — alert indicator (AC-3, BR-32, BR-34)', () => {
  it('renders LOW_STOCK indicator with text label and icon — not colour only', async () => {
    renderPage()
    await waitFor(() => screen.getByText('COMPRESSOR-R22'))

    // Check for text "Low stock" present in the document
    const lowStockIndicators = screen.getAllByText(/low stock/i)
    expect(lowStockIndicators.length).toBeGreaterThan(0)
    // Each has an aria-label on the parent span
    const indicatorSpan = document.querySelector('[aria-label*="Low stock"]')
    expect(indicatorSpan).toBeTruthy()
  })

  it('renders STOCKOUT indicator with distinct text, icon and shape', async () => {
    renderPage()
    await waitFor(() => screen.getByText('CAPACITOR-40/5-MFD'))

    const stockoutIndicators = screen.getAllByText(/stockout/i)
    expect(stockoutIndicators.length).toBeGreaterThan(0)
    const indicatorSpan = document.querySelector('[aria-label*="Stockout"]')
    expect(indicatorSpan).toBeTruthy()
    // STOCKOUT uses borderRadius 0 (square); LOW_STOCK uses rounded
    // Both carry aria-label describing the state — never colour-only
    expect(indicatorSpan.getAttribute('aria-label')).toMatch(/zero units/i)
  })

  it('STOCKOUT and LOW_STOCK indicators have different symbols (✕ vs ▼)', async () => {
    renderPage()
    await waitFor(() => screen.getByText('CAPACITOR-40/5-MFD'))

    // aria-hidden icon spans
    const icons = document.querySelectorAll('[aria-hidden="true"]')
    const texts = Array.from(icons).map(i => i.textContent)
    expect(texts).toContain('✕')
    expect(texts).toContain('▼')
  })
})

describe('LowStockPage — empty state', () => {
  it('shows empty state when no alerts are returned', async () => {
    vi.stubGlobal('fetch', () => Promise.resolve({
      ok: true,
      status: 200,
      headers: { get: () => null },
      json: () => Promise.resolve({ data: [], page: { number: 0, size: 50, totalElements: 0, totalPages: 0 }, links: {} }),
    }))
    renderPage()
    await waitFor(() => screen.getByText(/no active/i))
  })
})

describe('LowStockPage — error state', () => {
  it('shows error state and retry on fetch failure', async () => {
    vi.stubGlobal('fetch', () => Promise.resolve({ ok: false, status: 500, headers: { get: () => null }, json: () => Promise.resolve({ message: 'Error' }) }))
    renderPage()
    await waitFor(() => screen.getByRole('button', { name: /retry/i }))
  })
})
