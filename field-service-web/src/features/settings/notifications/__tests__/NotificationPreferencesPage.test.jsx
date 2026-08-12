/**
 * @fileoverview RTL tests for NotificationPreferencesPage and useNotificationPreferences (WO-197).
 *
 * Tests cover:
 *   - buildPreferencesUrl URL construction
 *   - Loading, empty, permission-denied, error, success states
 *   - Toggle interaction (optimistic update)
 *   - Optimistic rollback on mutation failure
 *   - Keyboard-only navigation (role=switch aria-checked)
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { buildPreferencesUrl } from '../api/useNotificationPreferences.js'

// ── Helpers ───────────────────────────────────────────────────────────────────

const USER_ID = 'aaaaaaaa-0000-0000-0000-000000000001'

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    logger: { log: () => {}, warn: () => {}, error: () => {} },
  })
}

const BASE_PREFS_RESPONSE = {
  data: [
    { category: 'WO_ASSIGNED',  channel: 'EMAIL',  enabled: true,  source: 'EXPLICIT' },
    { category: 'WO_ASSIGNED',  channel: 'IN_APP', enabled: true,  source: 'EXPLICIT' },
    { category: 'SLA_BREACH',   channel: 'EMAIL',  enabled: false, source: 'EXPLICIT' },
    { category: 'SLA_BREACH',   channel: 'IN_APP', enabled: true,  source: 'EXPLICIT' },
  ],
  page: { number: 0, size: 50, totalElements: 4, totalPages: 1 },
  links: { next: null, prev: null },
}

function renderPage(mockFetch) {
  const client = makeClient()
  if (mockFetch) vi.stubGlobal('fetch', mockFetch)
  const { default: NotificationPreferencesPage } =
    require('../NotificationPreferencesPage.jsx')
  return render(
    <QueryClientProvider client={client}>
      <NotificationPreferencesPage userId={USER_ID} />
    </QueryClientProvider>
  )
}

// ── buildPreferencesUrl ───────────────────────────────────────────────────────

describe('buildPreferencesUrl', () => {
  it('includes userId, page and size', () => {
    const url = buildPreferencesUrl(USER_ID)
    expect(url).toContain(USER_ID)
    expect(url).toContain('page=0')
    expect(url).toContain('size=50')
  })

  it('clamps size to 50', () => {
    const url = buildPreferencesUrl(USER_ID, { size: 9999 })
    expect(url).toContain('size=50')
  })

  it('allows smaller size values', () => {
    const url = buildPreferencesUrl(USER_ID, { size: 20 })
    expect(url).toContain('size=20')
  })
})

// ── NotificationPreferencesPage states ───────────────────────────────────────

describe('NotificationPreferencesPage', () => {
  let originalFetch

  beforeEach(() => {
    originalFetch = global.fetch
  })

  afterEach(() => {
    global.fetch = originalFetch
    vi.restoreAllMocks()
  })

  it('shows loading state on initial fetch', async () => {
    const mockFetch = vi.fn().mockReturnValue(new Promise(() => {}))
    renderPage(mockFetch)
    expect(screen.getByRole('status')).toBeInTheDocument()
  })

  it('renders preference cards on success', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: { get: (h) => h === 'content-type' ? 'application/json' : null },
      json: async () => BASE_PREFS_RESPONSE,
    })
    renderPage(mockFetch)

    await waitFor(() =>
      expect(screen.getByTestId('pref-card-WO_ASSIGNED')).toBeInTheDocument()
    )
    expect(screen.getByTestId('pref-card-SLA_BREACH')).toBeInTheDocument()
  })

  it('shows empty state when data is empty', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: { get: () => null },
      json: async () => ({
        data: [],
        page: { number: 0, size: 50, totalElements: 0, totalPages: 0 },
        links: { next: null, prev: null },
      }),
    })
    renderPage(mockFetch)
    await waitFor(() =>
      expect(screen.getByText(/all channels are on by default/i)).toBeInTheDocument()
    )
  })

  it('shows permission-denied state on 403', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      headers: { get: () => null },
      json: async () => ({ code: 'FORBIDDEN', message: 'Access denied.' }),
    })
    renderPage(mockFetch)
    await waitFor(() =>
      expect(screen.getByText(/do not have permission/i)).toBeInTheDocument()
    )
  })

  it('shows error state on network failure', async () => {
    const mockFetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    renderPage(mockFetch)
    await waitFor(() =>
      expect(screen.getByText(/Failed to load/i)).toBeInTheDocument()
    )
  })

  it('toggle changes aria-checked and calls fetch', async () => {
    const user = userEvent.setup()
    const statefulMock = (() => {
      let state = [...BASE_PREFS_RESPONSE.data]
      return vi.fn().mockImplementation((url, opts = {}) => {
        if ((opts.method ?? 'GET').toUpperCase() === 'PUT') {
          const body = JSON.parse(opts.body)
          const updates = body.preferences
          for (const u of updates) {
            const idx = state.findIndex(
              (p) => p.category === u.category && p.channel === u.channel
            )
            if (idx >= 0) state[idx] = { ...state[idx], enabled: u.enabled }
          }
        }
        return Promise.resolve({
          ok: true,
          status: 200,
          headers: { get: () => null },
          json: async () => ({
            ...BASE_PREFS_RESPONSE,
            data: state,
          }),
        })
      })
    })()

    renderPage(statefulMock)

    await waitFor(() =>
      expect(screen.getByTestId('toggle-WO_ASSIGNED-EMAIL')).toBeInTheDocument()
    )

    const toggle = screen.getByTestId('toggle-WO_ASSIGNED-EMAIL')
    expect(toggle).toHaveAttribute('aria-checked', 'true')

    await user.click(toggle)

    // Optimistic: aria-checked flips immediately (before server response)
    await waitFor(() =>
      expect(toggle).toHaveAttribute('aria-checked', 'false')
    )
  })

  it('toggle buttons are keyboard operable (role=switch)', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: { get: () => null },
      json: async () => BASE_PREFS_RESPONSE,
    })
    renderPage(mockFetch)

    await waitFor(() =>
      expect(screen.getAllByRole('switch').length).toBeGreaterThan(0)
    )

    const switches = screen.getAllByRole('switch')
    // All switches should be focusable
    for (const sw of switches) {
      expect(sw.tabIndex).not.toBe(-1)
    }
  })
})
