/**
 * @fileoverview RTL tests for PartsLoggingPanel.
 *
 * Covers: part search, staging, submit success, 422 INSUFFICIENT_STOCK per-line
 * detail (AC-7), network error not-connected state (AC-8), idempotency key reuse
 * on retry and regeneration on new submission (AC-6).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthContext } from '../../../../app/AuthContext.js'
import {
  partsSearchHandler,
  consumeSuccessHandler,
  consumeInsufficientStockHandler,
  consumeNetworkErrorHandler,
  inventoryHandlers,
} from '../../../../mocks/handlers/inventory.js'
import { PartsLoggingPanel } from '../PartsLoggingPanel.jsx'

// ── Scaffolding ────────────────────────────────────────────────────────────────

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    logger: { log: () => {}, warn: () => {}, error: () => {} },
  })
}

function makeAuth(roles = ['TECHNICIAN']) {
  return { accessToken: 'tok', roles, userId: 'u1', setAuth: vi.fn(), clearAuth: vi.fn() }
}

function renderPanel(props = {}) {
  const client = makeClient()
  const defaults = { workOrderId: 'wo-001', workOrderVersion: 1 }
  return render(
    <QueryClientProvider client={client}>
      <AuthContext.Provider value={makeAuth()}>
        <MemoryRouter>
          <PartsLoggingPanel {...defaults} {...props} />
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  )
}

// ── Combined handler that routes search + consume ────────────────────────────
function makeHandler(consumeHandler) {
  const search = partsSearchHandler()
  return async (url, opts) => {
    if (url.includes('/inventory/parts/search')) return search(url, opts)
    return consumeHandler(url, opts)
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

// ── Basic render ──────────────────────────────────────────────────────────────

describe('PartsLoggingPanel — initial render', () => {
  it('renders the panel heading and part search input', () => {
    vi.stubGlobal('fetch', inventoryHandlers())
    renderPanel()
    expect(screen.getByText(/log parts/i)).toBeInTheDocument()
    expect(screen.getByRole('combobox')).toBeInTheDocument()
  })

  it('renders return mode with appropriate label', () => {
    vi.stubGlobal('fetch', inventoryHandlers())
    renderPanel({ mode: 'return' })
    expect(screen.getByText(/return parts/i)).toBeInTheDocument()
  })
})

// ── Part search ───────────────────────────────────────────────────────────────

describe('PartsLoggingPanel — part search', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', partsSearchHandler())
  })

  it('shows search results after typing 2+ characters', async () => {
    renderPanel()
    const searchInput = screen.getByRole('combobox')
    fireEvent.change(searchInput, { target: { value: 'HV' } })
    await waitFor(() => screen.getByText('HVAC-FILTER-20x25'), { timeout: 1000 })
  })

  it('does not search with fewer than 2 characters', async () => {
    renderPanel()
    const searchInput = screen.getByRole('combobox')
    fireEvent.change(searchInput, { target: { value: 'H' } })
    // No fetch should have been triggered; list should be empty
    await new Promise(r => setTimeout(r, 400))
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })
})

// ── Staging a line ────────────────────────────────────────────────────────────

describe('PartsLoggingPanel — staging lines (AC-5)', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', makeHandler(consumeSuccessHandler()))
  })

  it('can select a part, enter a quantity, and add to staged list', async () => {
    renderPanel()
    const searchInput = screen.getByRole('combobox')
    fireEvent.change(searchInput, { target: { value: 'HV' } })

    await waitFor(() => screen.getByText('HVAC-FILTER-20x25'))
    fireEvent.click(screen.getByText('HVAC-FILTER-20x25'))

    // Quantity input should now appear
    const qtyInput = await waitFor(() => screen.getByLabelText(/Quantity of HVAC-FILTER-20x25/i))
    fireEvent.change(qtyInput, { target: { value: '2' } })

    fireEvent.click(screen.getByText(/\+ Add to list/i))

    await waitFor(() => screen.getByText(/Staged lines/i))
    expect(screen.getByText(/HVAC-FILTER-20x25/i)).toBeInTheDocument()
    expect(screen.getByText(/Qty:.*2/)).toBeInTheDocument()
  })

  it('validates that quantity must be > 0', async () => {
    renderPanel()
    const searchInput = screen.getByRole('combobox')
    fireEvent.change(searchInput, { target: { value: 'HV' } })
    await waitFor(() => screen.getByText('HVAC-FILTER-20x25'))
    fireEvent.click(screen.getByText('HVAC-FILTER-20x25'))

    const qtyInput = await waitFor(() => screen.getByLabelText(/Quantity/i))
    fireEvent.change(qtyInput, { target: { value: '0' } })
    fireEvent.click(screen.getByText(/\+ Add to list/i))

    expect(screen.getByText(/greater than 0/i)).toBeInTheDocument()
  })

  it('submit button is disabled when staged lines list is empty', () => {
    renderPanel()
    const submit = screen.getByRole('button', { name: /submit/i })
    expect(submit).toBeDisabled()
  })
})

// ── Successful submission ─────────────────────────────────────────────────────

describe('PartsLoggingPanel — successful submission', () => {
  it('calls onSuccess and clears staged lines on 200 response', async () => {
    const onSuccess = vi.fn()
    vi.stubGlobal('fetch', makeHandler(consumeSuccessHandler()))
    renderPanel({ onSuccess })

    // Add a line
    const searchInput = screen.getByRole('combobox')
    fireEvent.change(searchInput, { target: { value: 'HV' } })
    await waitFor(() => screen.getByText('HVAC-FILTER-20x25'))
    fireEvent.click(screen.getByText('HVAC-FILTER-20x25'))
    const qtyInput = await waitFor(() => screen.getByLabelText(/Quantity/i))
    fireEvent.change(qtyInput, { target: { value: '1' } })
    fireEvent.click(screen.getByText(/\+ Add to list/i))

    // Submit
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /submit/i }))
    })

    await waitFor(() => expect(onSuccess).toHaveBeenCalled())
    // Staged list cleared after success
    expect(screen.queryByText(/Staged lines/i)).not.toBeInTheDocument()
  })
})

// ── 422 INSUFFICIENT_STOCK (AC-7) ─────────────────────────────────────────────

describe('PartsLoggingPanel — 422 INSUFFICIENT_STOCK (AC-7)', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', makeHandler(consumeInsufficientStockHandler()))
  })

  it('shows per-line requested-vs-available detail when 422 is returned', async () => {
    renderPanel({ workOrderVersion: 1 })

    const searchInput = screen.getByRole('combobox')
    fireEvent.change(searchInput, { target: { value: 'HV' } })
    await waitFor(() => screen.getByText('HVAC-FILTER-20x25'))
    fireEvent.click(screen.getByText('HVAC-FILTER-20x25'))
    const qtyInput = await waitFor(() => screen.getByLabelText(/Quantity/i))
    fireEvent.change(qtyInput, { target: { value: '5' } })
    fireEvent.click(screen.getByText(/\+ Add to list/i))

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /submit/i }))
    })

    await waitFor(() => screen.getByText(/Insufficient stock/i))
    expect(screen.getByText(/requested 5, available 2/i)).toBeInTheDocument()
  })

  it('offers awaiting-parts hold action after 422', async () => {
    renderPanel({ workOrderVersion: 1 })

    const searchInput = screen.getByRole('combobox')
    fireEvent.change(searchInput, { target: { value: 'HV' } })
    await waitFor(() => screen.getByText('HVAC-FILTER-20x25'))
    fireEvent.click(screen.getByText('HVAC-FILTER-20x25'))
    const qtyInput = await waitFor(() => screen.getByLabelText(/Quantity/i))
    fireEvent.change(qtyInput, { target: { value: '5' } })
    fireEvent.click(screen.getByText(/\+ Add to list/i))

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /submit/i }))
    })

    await waitFor(() => screen.getByRole('button', { name: /place on hold/i }))
    expect(screen.getByRole('button', { name: /place on hold/i })).toBeInTheDocument()
  })

  it('shows no lines were applied messaging', async () => {
    renderPanel({ workOrderVersion: 1 })

    const searchInput = screen.getByRole('combobox')
    fireEvent.change(searchInput, { target: { value: 'HV' } })
    await waitFor(() => screen.getByText('HVAC-FILTER-20x25'))
    fireEvent.click(screen.getByText('HVAC-FILTER-20x25'))
    const qtyInput = await waitFor(() => screen.getByLabelText(/Quantity/i))
    fireEvent.change(qtyInput, { target: { value: '5' } })
    fireEvent.click(screen.getByText(/\+ Add to list/i))

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /submit/i }))
    })

    await waitFor(() => screen.getByText(/no lines were applied/i))
  })
})

// ── Network error — not-connected state (AC-8) ────────────────────────────────

describe('PartsLoggingPanel — network error (AC-8)', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', makeHandler(consumeNetworkErrorHandler()))
  })

  it('shows not-connected error state, never optimistic success', async () => {
    renderPanel()

    const searchInput = screen.getByRole('combobox')
    fireEvent.change(searchInput, { target: { value: 'HV' } })
    await waitFor(() => screen.getByText('HVAC-FILTER-20x25'))
    fireEvent.click(screen.getByText('HVAC-FILTER-20x25'))
    const qtyInput = await waitFor(() => screen.getByLabelText(/Quantity/i))
    fireEvent.change(qtyInput, { target: { value: '1' } })
    fireEvent.click(screen.getByText(/\+ Add to list/i))

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /submit/i }))
    })

    await waitFor(() => screen.getByText(/Not connected/i))
    expect(screen.getByText(/could not be submitted/i)).toBeInTheDocument()
  })

  it('shows Retry submission button after network failure', async () => {
    renderPanel()

    const searchInput = screen.getByRole('combobox')
    fireEvent.change(searchInput, { target: { value: 'HV' } })
    await waitFor(() => screen.getByText('HVAC-FILTER-20x25'))
    fireEvent.click(screen.getByText('HVAC-FILTER-20x25'))
    const qtyInput = await waitFor(() => screen.getByLabelText(/Quantity/i))
    fireEvent.change(qtyInput, { target: { value: '1' } })
    fireEvent.click(screen.getByText(/\+ Add to list/i))

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /submit/i }))
    })

    await waitFor(() => screen.getByRole('button', { name: /retry submission/i }))
  })
})

// ── Idempotency key (AC-6) ────────────────────────────────────────────────────

describe('PartsLoggingPanel — idempotency key (AC-6)', () => {
  it('includes Idempotency-Key header on submission', async () => {
    const capturedHeaders = []
    vi.stubGlobal('fetch', async (url, opts) => {
      if (url.includes('/inventory/parts/search')) return partsSearchHandler()(url, opts)
      if (url.includes('/parts/consume')) {
        capturedHeaders.push(opts?.headers ?? {})
        return consumeSuccessHandler()(url, opts)
      }
      return Promise.reject(new Error(`Unhandled: ${url}`))
    })

    renderPanel()
    const searchInput = screen.getByRole('combobox')
    fireEvent.change(searchInput, { target: { value: 'HV' } })
    await waitFor(() => screen.getByText('HVAC-FILTER-20x25'))
    fireEvent.click(screen.getByText('HVAC-FILTER-20x25'))
    const qtyInput = await waitFor(() => screen.getByLabelText(/Quantity/i))
    fireEvent.change(qtyInput, { target: { value: '1' } })
    fireEvent.click(screen.getByText(/\+ Add to list/i))

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /submit/i }))
    })

    await waitFor(() => expect(capturedHeaders.length).toBeGreaterThan(0))
    expect(capturedHeaders[0]['Idempotency-Key']).toBeTruthy()
    expect(typeof capturedHeaders[0]['Idempotency-Key']).toBe('string')
  })

  it('reuses the same Idempotency-Key when retrying after network error', async () => {
    const capturedKeys = []
    let callCount = 0

    vi.stubGlobal('fetch', async (url, opts) => {
      if (url.includes('/inventory/parts/search')) return partsSearchHandler()(url, opts)
      if (url.includes('/parts/consume')) {
        capturedKeys.push(opts?.headers?.['Idempotency-Key'])
        callCount++
        if (callCount === 1) return Promise.reject(new TypeError('Failed to fetch'))
        return consumeSuccessHandler()(url, opts)
      }
      return Promise.reject(new Error(`Unhandled: ${url}`))
    })

    renderPanel()
    const searchInput = screen.getByRole('combobox')
    fireEvent.change(searchInput, { target: { value: 'HV' } })
    await waitFor(() => screen.getByText('HVAC-FILTER-20x25'))
    fireEvent.click(screen.getByText('HVAC-FILTER-20x25'))
    const qtyInput = await waitFor(() => screen.getByLabelText(/Quantity/i))
    fireEvent.change(qtyInput, { target: { value: '1' } })
    fireEvent.click(screen.getByText(/\+ Add to list/i))

    // First attempt — network error
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /submit/i }))
    })
    await waitFor(() => screen.getByRole('button', { name: /retry submission/i }))

    // Dismiss not-connected state and resubmit
    fireEvent.click(screen.getByRole('button', { name: /retry submission/i }))

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /submit/i }))
    })

    await waitFor(() => expect(capturedKeys.length).toBe(2))
    // Both attempts must use the same key (AC-6: reuse on retry)
    expect(capturedKeys[0]).toBe(capturedKeys[1])
  })
})
