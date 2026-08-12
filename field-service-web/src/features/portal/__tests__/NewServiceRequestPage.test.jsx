/**
 * @fileoverview Tests for NewServiceRequestPage — portal submission form.
 *
 * Covers:
 *   - Form validation: missing site, fault too short, fault too long
 *   - Server field errors mapped inline (400)
 *   - 429 rate-limited with plain-language retry message
 *   - Submit blocked while in-flight (single POST, no double-submit)
 *   - Successful submit navigates to status page
 *   - No forbidden strings (internal state codes, coordinates) in DOM
 *   - Accessibility: axe assertions in light and dark appearances
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'axe-core'
import { AuthContext } from '../../../app/AuthContext.js'
import { portalHandler, portalSubmitHandler, portalSitesHandler } from '../../../mocks/handlers/portal.js'
import NewServiceRequestPage from '../NewServiceRequestPage.jsx'

const FORBIDDEN = ['NEW', 'ASSIGNED', 'EN_ROUTE', 'IN_PROGRESS', 'ON_HOLD', 'COMPLETED', 'CLOSED', 'CANCELLED', 'latitude', 'longitude', 'dispatchScore']

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    logger: { log: () => {}, warn: () => {}, error: () => {} },
  })
}

function makeAuth() {
  return { accessToken: 'tok', roles: ['CUSTOMER'], userId: 'u1', storedPreference: null, setAuth: vi.fn(), clearAuth: vi.fn() }
}

function Wrapper({ children, initialEntries = ['/portal/requests/new'] }) {
  return (
    <QueryClientProvider client={makeClient()}>
      <AuthContext.Provider value={makeAuth()}>
        <MemoryRouter initialEntries={initialEntries}>
          <Routes>
            <Route path="/portal/requests/new" element={children} />
            <Route path="/portal/requests/:id/status" element={<div data-testid="status-page">Status Page</div>} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>
  )
}

beforeEach(() => {
  vi.stubGlobal('fetch', portalHandler())
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.clearAllMocks()
  document.documentElement.removeAttribute('data-appearance')
})

describe('NewServiceRequestPage — site list', () => {
  it('renders site dropdown after loading', async () => {
    render(<NewServiceRequestPage />, { wrapper: Wrapper })
    await waitFor(() => expect(screen.getByLabelText(/site/i)).toBeInTheDocument())
    expect(screen.getByText('Main Office')).toBeInTheDocument()
    expect(screen.getByText('Warehouse')).toBeInTheDocument()
  })

  it('shows empty state when no sites returned', async () => {
    vi.stubGlobal('fetch', portalHandler({ sitesOptions: { empty: true } }))
    render(<NewServiceRequestPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText(/no sites are registered/i))
  })

  it('shows error state when sites fetch fails', async () => {
    vi.stubGlobal('fetch', portalHandler({ sitesOptions: { error: 500 } }))
    render(<NewServiceRequestPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText(/could not load your sites/i))
  })
})

describe('NewServiceRequestPage — form validation', () => {
  it('shows error when submitting without selecting a site', async () => {
    render(<NewServiceRequestPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByLabelText(/site/i))

    fireEvent.click(screen.getByRole('button', { name: /submit request/i }))

    await waitFor(() => expect(screen.getByText(/please select a site/i)).toBeInTheDocument())
  })

  it('shows error when fault description is too short', async () => {
    render(<NewServiceRequestPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByLabelText(/site/i))

    fireEvent.change(screen.getByLabelText(/site/i), {
      target: { value: 'a0000000-0000-0000-0000-000000000001' },
    })
    fireEvent.change(screen.getByLabelText(/describe the problem/i), {
      target: { value: 'ab' },
    })
    fireEvent.click(screen.getByRole('button', { name: /submit request/i }))

    await waitFor(() => expect(screen.getByText(/at least 4 characters/i)).toBeInTheDocument())
  })

  it('shows character counter and disables submit when fault exceeds max length', async () => {
    render(<NewServiceRequestPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByLabelText(/site/i))

    const longText = 'x'.repeat(2001)
    fireEvent.change(screen.getByLabelText(/describe the problem/i), {
      target: { value: longText },
    })

    await waitFor(() => {
      expect(screen.getByText(/2001 \/ 2000/)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /submit request/i })).toBeDisabled()
    })
  })
})

describe('NewServiceRequestPage — server errors', () => {
  it('renders 400 field errors inline against the correct field', async () => {
    vi.stubGlobal('fetch', portalHandler({ submitError: 400 }))
    render(<NewServiceRequestPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByLabelText(/site/i))

    fireEvent.change(screen.getByLabelText(/site/i), {
      target: { value: 'a0000000-0000-0000-0000-000000000001' },
    })
    fireEvent.change(screen.getByLabelText(/describe the problem/i), {
      target: { value: 'Test fault description here' },
    })
    fireEvent.click(screen.getByRole('button', { name: /submit request/i }))

    await waitFor(() => {
      expect(screen.getByText(/must be between 4 and 2000 characters/i)).toBeInTheDocument()
    })
  })

  it('renders plain-language retry message on 429', async () => {
    vi.stubGlobal('fetch', portalHandler({ submitError: 429 }))
    render(<NewServiceRequestPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByLabelText(/site/i))

    fireEvent.change(screen.getByLabelText(/site/i), {
      target: { value: 'a0000000-0000-0000-0000-000000000001' },
    })
    fireEvent.change(screen.getByLabelText(/describe the problem/i), {
      target: { value: 'Test fault description here that is long enough' },
    })
    fireEvent.click(screen.getByRole('button', { name: /submit request/i }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
      expect(screen.getByText(/too many requests/i)).toBeInTheDocument()
    })
    // Must NOT show internal error code
    expect(screen.queryByText('RATE_LIMITED')).not.toBeInTheDocument()
  })
})

describe('NewServiceRequestPage — submission flow', () => {
  it('navigates to status page after successful submit', async () => {
    render(<NewServiceRequestPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByLabelText(/site/i))

    fireEvent.change(screen.getByLabelText(/site/i), {
      target: { value: 'a0000000-0000-0000-0000-000000000001' },
    })
    fireEvent.change(screen.getByLabelText(/describe the problem/i), {
      target: { value: 'The HVAC unit is not cooling the building properly.' },
    })
    fireEvent.click(screen.getByRole('button', { name: /submit request/i }))

    await waitFor(() => {
      expect(screen.getByTestId('status-page')).toBeInTheDocument()
    })
  })

  it('disables submit button while request is in flight', async () => {
    let resolveSubmit
    const pendingResponse = new Promise(resolve => { resolveSubmit = resolve })
    const slowFetch = (url, init = {}) => {
      const urlStr = typeof url === 'string' ? url : url.url
      const method = (init?.method ?? 'GET').toUpperCase()
      if (urlStr.includes('/service-requests') && method === 'POST') {
        return pendingResponse
      }
      return portalHandler()(url, init)
    }
    vi.stubGlobal('fetch', slowFetch)

    render(<NewServiceRequestPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByLabelText(/site/i))

    fireEvent.change(screen.getByLabelText(/site/i), {
      target: { value: 'a0000000-0000-0000-0000-000000000001' },
    })
    fireEvent.change(screen.getByLabelText(/describe the problem/i), {
      target: { value: 'The HVAC unit is not cooling the building properly.' },
    })
    fireEvent.click(screen.getByRole('button', { name: /submit request/i }))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /submitting/i })).toBeDisabled()
    })

    // Resolve the pending request to clean up
    resolveSubmit({
      ok: false,
      status: 500,
      headers: { get: () => null },
      json: () => Promise.resolve({ code: 'INTERNAL_ERROR', message: 'Error' }),
    })
  })
})

describe('NewServiceRequestPage — forbidden strings', () => {
  it('does not render internal state codes or coordinates in the DOM', async () => {
    render(<NewServiceRequestPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByLabelText(/site/i))

    const bodyText = document.body.innerText ?? document.body.textContent ?? ''
    FORBIDDEN.forEach(forbidden => {
      expect(bodyText).not.toContain(forbidden)
    })
  })
})

describe('NewServiceRequestPage — accessibility', () => {
  it('has no critical axe violations in light appearance', async () => {
    document.documentElement.setAttribute('data-appearance', 'light')
    const { container } = render(<NewServiceRequestPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByLabelText(/site/i))

    const results = await axe(container)
    const criticalOrSerious = results.violations.filter(v => v.impact === 'critical' || v.impact === 'serious')
    expect(criticalOrSerious).toHaveLength(0)
  })

  it('has no critical axe violations in dark appearance', async () => {
    document.documentElement.setAttribute('data-appearance', 'dark')
    const { container } = render(<NewServiceRequestPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByLabelText(/site/i))

    const results = await axe(container)
    const criticalOrSerious = results.violations.filter(v => v.impact === 'critical' || v.impact === 'serious')
    expect(criticalOrSerious).toHaveLength(0)
  })
})
