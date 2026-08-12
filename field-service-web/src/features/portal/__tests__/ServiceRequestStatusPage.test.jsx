/**
 * @fileoverview Tests for ServiceRequestStatusPage — portal status tracking.
 *
 * Covers:
 *   - Happy path: renders status label, description, technician, appointment
 *   - 304 polling: existing data retained, no loading skeleton re-render
 *   - Degraded banner: freshness.degraded=true shows degraded notice
 *   - Not-connected banner: network failure shows manual retry
 *   - 404 neutral not-found
 *   - No forbidden strings (internal codes, coordinates, technician PII) in DOM
 *   - Accessibility: axe assertions in light and dark appearances
 *   - Keyboard navigation: appearance toggle is reachable by keyboard
 *   - Live region announces status changes
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'axe-core'
import { AuthContext } from '../../../app/AuthContext.js'
import { AppearanceProvider } from '../../../appearance/AppearanceProvider.jsx'
import { portalStatusHandler } from '../../../mocks/handlers/portal.js'
import { _clearETagsForTesting } from '../../../api/useConditionalQuery.js'
import ServiceRequestStatusPage from '../ServiceRequestStatusPage.jsx'

const REQUEST_ID = 'c0000000-0000-0000-0000-000000000001'
const FORBIDDEN = [
  'NEW', 'ASSIGNED', 'EN_ROUTE', 'IN_PROGRESS', 'ON_HOLD',
  'COMPLETED', 'CLOSED', 'CANCELLED',
  'latitude', 'longitude', 'dispatchScore',
]

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    logger: { log: () => {}, warn: () => {}, error: () => {} },
  })
}

function makeAuth() {
  return { accessToken: 'tok', roles: ['CUSTOMER'], userId: 'u1', storedPreference: null, setAuth: vi.fn(), clearAuth: vi.fn() }
}

function Wrapper({ children, initialPath = `/portal/requests/${REQUEST_ID}/status` }) {
  return (
    <QueryClientProvider client={makeClient()}>
      <AuthContext.Provider value={makeAuth()}>
        <AppearanceProvider>
          <MemoryRouter initialEntries={[initialPath]}>
            <Routes>
              <Route path="/portal/requests/:id/status" element={children} />
            </Routes>
          </MemoryRouter>
        </AppearanceProvider>
      </AuthContext.Provider>
    </QueryClientProvider>
  )
}

beforeEach(() => {
  _clearETagsForTesting()
  vi.stubGlobal('fetch', portalStatusHandler({ state: 'assigned' }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.clearAllMocks()
  document.documentElement.removeAttribute('data-appearance')
})

describe('ServiceRequestStatusPage — happy path', () => {
  it('renders status label from API', async () => {
    render(<ServiceRequestStatusPage />, { wrapper: Wrapper })
    await waitFor(() => {
      expect(screen.getByText('Technician Assigned')).toBeInTheDocument()
    })
  })

  it('renders plain-language description', async () => {
    render(<ServiceRequestStatusPage />, { wrapper: Wrapper })
    await waitFor(() => {
      expect(screen.getByText(/a technician has been assigned/i)).toBeInTheDocument()
    })
  })

  it('renders technician first name and role label only (no PII)', async () => {
    render(<ServiceRequestStatusPage />, { wrapper: Wrapper })
    await waitFor(() => {
      expect(screen.getByText(/alice/i)).toBeInTheDocument()
      expect(screen.getByText(/senior technician/i)).toBeInTheDocument()
    })
  })

  it('renders appointment window when present', async () => {
    render(<ServiceRequestStatusPage />, { wrapper: Wrapper })
    await waitFor(() => {
      expect(screen.getByLabelText(/scheduled appointment/i)).toBeInTheDocument()
    })
  })

  it('renders milestone history', async () => {
    render(<ServiceRequestStatusPage />, { wrapper: Wrapper })
    await waitFor(() => {
      expect(screen.getByText('Request Received')).toBeInTheDocument()
      expect(screen.getByText('Technician Assigned')).toBeInTheDocument()
    })
  })

  it('renders freshness indicator', async () => {
    render(<ServiceRequestStatusPage />, { wrapper: Wrapper })
    await waitFor(() => {
      expect(screen.getByTestId('freshness-indicator')).toBeInTheDocument()
      expect(screen.getByText(/last updated/i)).toBeInTheDocument()
    })
  })

  it('does not render technician section when no technician in payload', async () => {
    vi.stubGlobal('fetch', portalStatusHandler({ state: 'new' }))
    render(<ServiceRequestStatusPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText('Request Received'))
    expect(screen.queryByLabelText(/assigned technician/i)).not.toBeInTheDocument()
  })
})

describe('ServiceRequestStatusPage — conditional GET', () => {
  it('retains previously rendered data after a 304 response (no skeleton re-render)', async () => {
    vi.stubGlobal('fetch', portalStatusHandler({ state: 'assigned', sequence: ['200', '304'] }))

    render(<ServiceRequestStatusPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText('Technician Assigned'))

    // At no point during the 304 re-fetch should there be a loading skeleton
    expect(screen.queryByText(/loading/i)).not.toBeInTheDocument()
    expect(screen.getByText('Technician Assigned')).toBeInTheDocument()
  })
})

describe('ServiceRequestStatusPage — degraded and not-connected states', () => {
  it('shows degraded banner when freshness.degraded is true', async () => {
    vi.stubGlobal('fetch', portalStatusHandler({ state: 'degraded' }))
    render(<ServiceRequestStatusPage />, { wrapper: Wrapper })
    await waitFor(() => {
      expect(screen.getByTestId('degraded-banner')).toBeInTheDocument()
      expect(screen.getByText(/some information may be incomplete/i)).toBeInTheDocument()
    })
  })

  it('shows not-connected banner on fetch error with retry button', async () => {
    vi.stubGlobal('fetch', () => Promise.reject(new Error('Network error')))
    render(<ServiceRequestStatusPage />, { wrapper: Wrapper })
    await waitFor(() => {
      expect(screen.getByTestId('not-connected-banner')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument()
    })
  })

  it('shows not-connected banner on 500 error', async () => {
    vi.stubGlobal('fetch', portalStatusHandler({ error: 500 }))
    render(<ServiceRequestStatusPage />, { wrapper: Wrapper })
    await waitFor(() => {
      expect(screen.getByTestId('not-connected-banner')).toBeInTheDocument()
    })
  })
})

describe('ServiceRequestStatusPage — 404', () => {
  it('renders not-connected state on 404 without disclosing internal info', async () => {
    vi.stubGlobal('fetch', portalStatusHandler({ error: 404 }))
    render(<ServiceRequestStatusPage />, { wrapper: Wrapper })
    await waitFor(() => {
      expect(screen.getByTestId('not-connected-banner')).toBeInTheDocument()
    })
    // Must NOT show any internal error code
    expect(screen.queryByText('PORTAL_RESOURCE_NOT_FOUND')).not.toBeInTheDocument()
  })
})

describe('ServiceRequestStatusPage — forbidden strings', () => {
  it('renders no internal state codes or location data in the DOM', async () => {
    render(<ServiceRequestStatusPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText('Technician Assigned'))

    const bodyText = document.body.textContent ?? ''
    FORBIDDEN.forEach(forbidden => {
      expect(bodyText).not.toContain(forbidden)
    })
  })
})

describe('ServiceRequestStatusPage — appearance toggle', () => {
  it('renders appearance toggle button', async () => {
    render(<ServiceRequestStatusPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText('Technician Assigned'))
    expect(screen.getByRole('button', { name: /appearance/i })).toBeInTheDocument()
  })

  it('appearance toggle button is keyboard accessible (has tab stop)', async () => {
    render(<ServiceRequestStatusPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText('Technician Assigned'))
    const btn = screen.getByRole('button', { name: /appearance/i })
    expect(btn.tabIndex).not.toBe(-1)
  })
})

describe('ServiceRequestStatusPage — accessibility', () => {
  it('has no critical axe violations in light appearance', async () => {
    document.documentElement.setAttribute('data-appearance', 'light')
    const { container } = render(<ServiceRequestStatusPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText('Technician Assigned'))

    const results = await axe(container)
    const criticalOrSerious = results.violations.filter(v => v.impact === 'critical' || v.impact === 'serious')
    expect(criticalOrSerious).toHaveLength(0)
  })

  it('has no critical axe violations in dark appearance', async () => {
    document.documentElement.setAttribute('data-appearance', 'dark')
    const { container } = render(<ServiceRequestStatusPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText('Technician Assigned'))

    const results = await axe(container)
    const criticalOrSerious = results.violations.filter(v => v.impact === 'critical' || v.impact === 'serious')
    expect(criticalOrSerious).toHaveLength(0)
  })

  it('uses a live region for status announcements', async () => {
    const { container } = render(<ServiceRequestStatusPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText('Technician Assigned'))

    const liveRegion = container.querySelector('[role="status"][aria-live]')
    expect(liveRegion).not.toBeNull()
  })

  it('all interactive controls are keyboard reachable (positive tabIndex or default)', async () => {
    render(<ServiceRequestStatusPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText('Technician Assigned'))

    const buttons = screen.getAllByRole('button')
    buttons.forEach(btn => {
      expect(btn.tabIndex).toBeGreaterThanOrEqual(0)
    })
  })
})
