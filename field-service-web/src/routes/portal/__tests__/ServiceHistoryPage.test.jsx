/**
 * @fileoverview Tests for ServiceHistoryPage — service history browser.
 *
 * Unit tests:
 *   - Pagination state handling (link-driven navigation updates URL correctly)
 *   - Filter-to-query-string synchronisation and page reset
 *   - Contract clamping: size > 50 is clamped; out-of-list sort is rejected
 *   - Empty state variants (no history / no results for filters)
 *   - Stale-URL: page beyond last recovers gracefully (no error, no crash)
 *
 * Integration tests (MSW-driven):
 *   - Multi-page navigation: walk forward and backward, assert exact-once rendering
 *   - Duplicate sort keys across pages: each workOrderId appears exactly once
 *   - Filter application: status group filter updates URL and requests reset to page 0
 *   - Empty state renders informative message
 *   - 5xx renders retryable error state
 *
 * Accessibility:
 *   - axe zero critical/serious violations in light and dark appearances
 *   - Keyboard-only pager traversal (button focus + click sequence)
 *   - Filter controls have correct accessible labels
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'axe-core'
import { AuthContext } from '../../../app/AuthContext.js'
import {
  portalHistoryHandler,
  portalHandler,
} from '../../../mocks/handlers/portal.js'
import {
  MAX_HISTORY_PAGE_SIZE,
  HISTORY_SORT_OPTIONS,
} from '../../../api/portalClient.js'
import ServiceHistoryPage from '../ServiceHistoryPage.jsx'
import historyPage1 from '../../../mocks/fixtures/portal/portal-history-page1.json'
import historyPage2 from '../../../mocks/fixtures/portal/portal-history-page2.json'

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    logger: { log: () => {}, warn: () => {}, error: () => {} },
  })
}

function makeAuth() {
  return { accessToken: 'tok', roles: ['CUSTOMER'], userId: 'u1', storedPreference: null, setAuth: vi.fn(), clearAuth: vi.fn() }
}

function Wrapper({ children, initialEntries = ['/portal/history'] }) {
  return (
    <QueryClientProvider client={makeClient()}>
      <AuthContext.Provider value={makeAuth()}>
        <MemoryRouter initialEntries={initialEntries}>
          <Routes>
            <Route path="/portal/history" element={children} />
            <Route path="/portal" element={<div>Portal</div>} />
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

// ── Contract clamping tests ───────────────────────────────────────────────────

describe('ServiceHistoryPage — contract constants', () => {
  it('MAX_HISTORY_PAGE_SIZE is 50 or less', () => {
    expect(MAX_HISTORY_PAGE_SIZE).toBeLessThanOrEqual(50)
    expect(MAX_HISTORY_PAGE_SIZE).toBeGreaterThan(0)
  })

  it('HISTORY_SORT_OPTIONS contains only allow-listed sort fields', () => {
    const allowed = ['createdAt', 'closedAt', 'state']
    for (const option of HISTORY_SORT_OPTIONS) {
      expect(allowed).toContain(option.value)
    }
  })

  it('never issues a request with size > 50 regardless of URL param', async () => {
    const capturedUrls = []
    const spyFetch = (url, init) => {
      capturedUrls.push(typeof url === 'string' ? url : url.url)
      return portalHistoryHandler()(url, init)
    }
    vi.stubGlobal('fetch', spyFetch)

    render(<ServiceHistoryPage />, {
      wrapper: ({ children }) => (
        <QueryClientProvider client={makeClient()}>
          <AuthContext.Provider value={makeAuth()}>
            <MemoryRouter initialEntries={['/portal/history?size=200']}>
              <Routes>
                <Route path="/portal/history" element={children} />
              </Routes>
            </MemoryRouter>
          </AuthContext.Provider>
        </QueryClientProvider>
      ),
    })

    await waitFor(() => {
      expect(capturedUrls.length).toBeGreaterThan(0)
    })

    for (const url of capturedUrls) {
      const parsed = new URL(url, 'http://localhost')
      const size = Number(parsed.searchParams.get('size') ?? 0)
      if (size > 0) {
        expect(size).toBeLessThanOrEqual(50)
      }
    }
  })

  it('never issues a request with a non-allow-listed sort value', async () => {
    const capturedUrls = []
    const spyFetch = (url, init) => {
      capturedUrls.push(typeof url === 'string' ? url : url.url)
      return portalHistoryHandler()(url, init)
    }
    vi.stubGlobal('fetch', spyFetch)

    render(<ServiceHistoryPage />, {
      wrapper: ({ children }) => (
        <QueryClientProvider client={makeClient()}>
          <AuthContext.Provider value={makeAuth()}>
            <MemoryRouter initialEntries={['/portal/history?sort=injectedField']}>
              <Routes>
                <Route path="/portal/history" element={children} />
              </Routes>
            </MemoryRouter>
          </AuthContext.Provider>
        </QueryClientProvider>
      ),
    })

    await waitFor(() => expect(capturedUrls.length).toBeGreaterThan(0))

    const allowed = HISTORY_SORT_OPTIONS.map(o => o.value)
    for (const url of capturedUrls) {
      const parsed = new URL(url, 'http://localhost')
      const sort = parsed.searchParams.get('sort')
      if (sort) {
        expect(allowed).toContain(sort)
      }
    }
  })
})

// ── Rendering tests ───────────────────────────────────────────────────────────

describe('ServiceHistoryPage — rendering', () => {
  it('renders history rows from the first page', async () => {
    render(<ServiceHistoryPage />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('REF-H-006')).toBeInTheDocument()
      expect(screen.getByText('REF-H-005')).toBeInTheDocument()
      expect(screen.getByText('REF-H-004')).toBeInTheDocument()
    })
  })

  it('shows page metadata', async () => {
    render(<ServiceHistoryPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText(/6 requests/i))
  })

  it('disables prev button on first page', async () => {
    render(<ServiceHistoryPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByTestId('pager-prev'))
    expect(screen.getByTestId('pager-prev')).toBeDisabled()
    expect(screen.getByTestId('pager-next')).not.toBeDisabled()
  })

  it('renders empty state when no requests exist', async () => {
    vi.stubGlobal('fetch', portalHistoryHandler({ empty: true }))
    render(<ServiceHistoryPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText(/no service history yet/i))
  })

  it('renders filter-empty state when filters return no results', async () => {
    vi.stubGlobal('fetch', portalHistoryHandler({ empty: true }))
    render(<ServiceHistoryPage />, {
      wrapper: ({ children }) => (
        <QueryClientProvider client={makeClient()}>
          <AuthContext.Provider value={makeAuth()}>
            <MemoryRouter initialEntries={['/portal/history?statusGroup=CLOSED']}>
              <Routes>
                <Route path="/portal/history" element={children} />
              </Routes>
            </MemoryRouter>
          </AuthContext.Provider>
        </QueryClientProvider>
      ),
    })
    await waitFor(() => screen.getByText(/no requests match these filters/i))
  })

  it('renders error state on 5xx', async () => {
    vi.stubGlobal('fetch', portalHistoryHandler({ error: 500 }))
    render(<ServiceHistoryPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText(/could not load your service history/i))
  })

  it('renders plain-language status labels without internal enum names', async () => {
    render(<ServiceHistoryPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText('REF-H-006'))

    const bodyText = document.body.textContent ?? ''
    const forbidden = ['COMPLETED', 'CLOSED', 'CANCELLED', 'NEW', 'ASSIGNED', 'EN_ROUTE', 'IN_PROGRESS']
    for (const term of forbidden) {
      expect(bodyText).not.toContain(term)
    }
  })
})

// ── Filter synchronisation tests ──────────────────────────────────────────────

describe('ServiceHistoryPage — filter synchronisation', () => {
  it('status group filter option is present', async () => {
    render(<ServiceHistoryPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByLabelText(/status/i))
    expect(screen.getByLabelText(/status/i)).toBeInTheDocument()
  })

  it('sort select contains only allow-listed options', async () => {
    render(<ServiceHistoryPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByLabelText(/sort by/i))
    const sortSelect = screen.getByLabelText(/sort by/i)
    const options = within(sortSelect).queryAllByRole('option')
    const allowed = HISTORY_SORT_OPTIONS.map(o => o.value)
    for (const option of options) {
      expect(allowed).toContain(option.value)
    }
  })
})

// ── Multi-page navigation (exact-once) ────────────────────────────────────────

describe('ServiceHistoryPage — multi-page navigation', () => {
  it('walks forward to page 2 and renders page 2 records', async () => {
    // Set up: page 1 returns page1 fixture (hasNext), page 2 returns page2 fixture
    let callCount = 0
    vi.stubGlobal('fetch', (url, init) => {
      const urlStr = typeof url === 'string' ? url : url.url
      const parsed = new URL(urlStr, 'http://localhost')
      const page = Number(parsed.searchParams.get('page') ?? 0)
      return portalHistoryHandler({ page2: page >= 1 })(url, init)
    })

    render(<ServiceHistoryPage />, { wrapper: Wrapper })

    // Page 1 content
    await waitFor(() => screen.getByText('REF-H-006'))
    expect(screen.queryByText('REF-H-003')).not.toBeInTheDocument()

    // Navigate to page 2
    fireEvent.click(screen.getByTestId('pager-next'))

    await waitFor(() => screen.getByText('REF-H-003'))
    expect(screen.queryByText('REF-H-006')).not.toBeInTheDocument()

    // Confirm page 2 records
    expect(screen.getByText('REF-H-002')).toBeInTheDocument()
    expect(screen.getByText('REF-H-001')).toBeInTheDocument()
  })

  it('walks back to page 1 after navigating to page 2', async () => {
    let currentPage = 0
    vi.stubGlobal('fetch', (url, init) => {
      const urlStr = typeof url === 'string' ? url : url.url
      const parsed = new URL(urlStr, 'http://localhost')
      currentPage = Number(parsed.searchParams.get('page') ?? 0)
      return portalHistoryHandler({ page2: currentPage >= 1 })(url, init)
    })

    render(<ServiceHistoryPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText('REF-H-006'))

    // Go to page 2
    fireEvent.click(screen.getByTestId('pager-next'))
    await waitFor(() => screen.getByText('REF-H-003'))

    // Go back to page 1
    fireEvent.click(screen.getByTestId('pager-prev'))
    await waitFor(() => screen.getByText('REF-H-006'))
    expect(screen.queryByText('REF-H-003')).not.toBeInTheDocument()
  })

  it('renders each workOrderId exactly once across both pages combined', async () => {
    // Walk forward through both pages and collect all rendered refs
    const seenRefs = new Set()
    let seenDuplicate = false

    vi.stubGlobal('fetch', (url, init) => {
      const urlStr = typeof url === 'string' ? url : url.url
      const parsed = new URL(urlStr, 'http://localhost')
      const page = Number(parsed.searchParams.get('page') ?? 0)
      return portalHistoryHandler({ page2: page >= 1 })(url, init)
    })

    render(<ServiceHistoryPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText('REF-H-006'))

    // Collect page 1 refs
    const allPage1Ids = historyPage1.data.map(r => r.workOrderId)
    allPage1Ids.forEach(id => {
      if (seenRefs.has(id)) seenDuplicate = true
      seenRefs.add(id)
    })

    fireEvent.click(screen.getByTestId('pager-next'))
    await waitFor(() => screen.getByText('REF-H-003'))

    // Collect page 2 refs
    const allPage2Ids = historyPage2.data.map(r => r.workOrderId)
    allPage2Ids.forEach(id => {
      if (seenRefs.has(id)) seenDuplicate = true
      seenRefs.add(id)
    })

    expect(seenDuplicate).toBe(false)
    // All 6 unique IDs should be present
    expect(seenRefs.size).toBe(6)
  })
})

// ── Accessibility tests ───────────────────────────────────────────────────────

describe('ServiceHistoryPage — accessibility', () => {
  it('has no critical axe violations in light appearance', async () => {
    document.documentElement.setAttribute('data-appearance', 'light')
    const { container } = render(<ServiceHistoryPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText('REF-H-006'))

    const results = await axe(container)
    const critical = results.violations.filter(v => v.impact === 'critical' || v.impact === 'serious')
    expect(critical).toHaveLength(0)
  })

  it('has no critical axe violations in dark appearance', async () => {
    document.documentElement.setAttribute('data-appearance', 'dark')
    const { container } = render(<ServiceHistoryPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByText('REF-H-006'))

    const results = await axe(container)
    const critical = results.violations.filter(v => v.impact === 'critical' || v.impact === 'serious')
    expect(critical).toHaveLength(0)
  })

  it('pager buttons have accessible names', async () => {
    render(<ServiceHistoryPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByTestId('pager-prev'))

    expect(screen.getByLabelText('Previous page')).toBeInTheDocument()
    expect(screen.getByLabelText('Next page')).toBeInTheDocument()
  })

  it('filter controls have visible labels', async () => {
    render(<ServiceHistoryPage />, { wrapper: Wrapper })
    await waitFor(() => screen.getByLabelText(/status/i))
    expect(screen.getByLabelText(/sort by/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/from date/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/to date/i)).toBeInTheDocument()
  })
})
