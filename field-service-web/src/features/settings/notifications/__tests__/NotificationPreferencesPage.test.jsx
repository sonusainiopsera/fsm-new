import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { resetNotificationPreferencesStore } from '../../../../mocks/handlers/notificationPreferences.js'
import { server } from '../../../../mocks/server.js'
import { NotificationPreferencesPage } from '../NotificationPreferencesPage.jsx'

/**
 * Creates a fresh QueryClient for each test to avoid cache bleed-over.
 * @returns {QueryClient}
 */
function makeQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        staleTime: Infinity,
        gcTime: Infinity,
      },
      mutations: { retry: false },
    },
  })
}

/**
 * Renders NotificationPreferencesPage with a fresh QueryClient.
 * @param {{ userId?: string }} [options]
 */
function renderPage(options = {}) {
  const { userId = 'user-123' } = options
  const queryClient = makeQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <NotificationPreferencesPage userId={userId} />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  resetNotificationPreferencesStore()
})

describe('NotificationPreferencesPage', () => {
  // -----------------------------------------------------------------------
  // 1. Renders loading state initially
  // -----------------------------------------------------------------------
  it('renders loading state initially', () => {
    // Use a handler that never resolves so we stay in loading state
    server.use(
      http.get('/api/v1/users/:userId/notification-preferences', () => {
        return new Promise(() => {}) // Never resolves
      })
    )

    renderPage()

    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(screen.getByText(/loading notification preferences/i)).toBeInTheDocument()
  })

  // -----------------------------------------------------------------------
  // 2. Renders preferences after data loads
  // -----------------------------------------------------------------------
  it('renders preferences after data loads', async () => {
    renderPage()

    // Wait for loading to finish
    await waitFor(() => {
      expect(screen.queryByText(/loading notification preferences/i)).not.toBeInTheDocument()
    })

    // The table should appear
    expect(screen.getByRole('table', { name: /notification preferences/i })).toBeInTheDocument()

    // Verify some category names are rendered
    expect(screen.getByText('Work Order Created')).toBeInTheDocument()
    expect(screen.getByText('SLA At Risk')).toBeInTheDocument()
    expect(screen.getByText('Technician En Route')).toBeInTheDocument()

    // Verify channel headers
    expect(screen.getByRole('columnheader', { name: /email/i })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: /sms/i })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: /in app/i })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: /push/i })).toBeInTheDocument()
  })

  // -----------------------------------------------------------------------
  // 3. Toggle a switch → optimistic update shows immediately
  // -----------------------------------------------------------------------
  it('toggles a switch and shows optimistic update immediately', async () => {
    const user = userEvent.setup()

    // Delay the PUT to verify optimistic update is visible before server responds
    let resolveUpdate = /** @type {(value?: unknown) => void} */ (() => {})
    server.use(
      http.put('/api/v1/users/:userId/notification-preferences', async ({ request }) => {
        await new Promise((resolve) => {
          resolveUpdate = resolve
        })
        const body = await request.json()
        return HttpResponse.json({
          data: /** @type {any[]} */ (/** @type {any} */ (body).preferences).map(
            /** @param {{ category: string, channel: string, enabled: boolean }} p */
            (p) => ({ ...p, source: 'EXPLICIT' })
          ),
          page: { number: 0, size: 100, totalElements: 1, totalPages: 1 },
          links: { next: null, prev: null },
        })
      })
    )

    renderPage()

    // Wait for data to load
    await waitFor(() => {
      expect(screen.queryByText(/loading/i)).not.toBeInTheDocument()
    })

    // Find the "Work Order Created — Email" switch (initially enabled per mock data)
    const emailSwitch = screen.getByRole('switch', {
      name: /work order created — email/i,
    })
    expect(emailSwitch).toHaveAttribute('aria-checked', 'true')

    // Toggle it off
    await user.click(emailSwitch)

    // Optimistic update: should be off immediately before server responds
    expect(emailSwitch).toHaveAttribute('aria-checked', 'false')

    // Resolve the server request
    resolveUpdate()

    // After settling it should still be off (server confirmed)
    await waitFor(() => {
      expect(emailSwitch).toHaveAttribute('aria-checked', 'false')
    })
  })

  // -----------------------------------------------------------------------
  // 4. Toggle fails → rolls back to previous state
  // -----------------------------------------------------------------------
  it('rolls back optimistic update when mutation fails', async () => {
    const user = userEvent.setup()

    // Override PUT to fail
    server.use(
      http.put('/api/v1/users/:userId/notification-preferences', () => {
        return HttpResponse.json({ error: 'Server error' }, { status: 500 })
      })
    )

    renderPage()

    await waitFor(() => {
      expect(screen.queryByText(/loading/i)).not.toBeInTheDocument()
    })

    const emailSwitch = screen.getByRole('switch', {
      name: /work order created — email/i,
    })
    const initialChecked = emailSwitch.getAttribute('aria-checked')

    // Toggle the switch
    await user.click(emailSwitch)

    // Wait for rollback — the switch should revert to its original value
    await waitFor(() => {
      expect(emailSwitch).toHaveAttribute('aria-checked', initialChecked)
    })

    // Error message should be displayed
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })

  // -----------------------------------------------------------------------
  // 5. Keyboard navigation: can navigate and toggle all switches with keyboard only
  // -----------------------------------------------------------------------
  it('supports full keyboard navigation and toggling', async () => {
    const user = userEvent.setup()

    renderPage()

    await waitFor(() => {
      expect(screen.queryByText(/loading/i)).not.toBeInTheDocument()
    })

    // Tab to the first switch
    await user.tab()

    // Find all switches
    const switches = screen.getAllByRole('switch')
    expect(switches.length).toBeGreaterThan(0)

    // The first switch should be focusable
    const firstSwitch = switches[0]
    firstSwitch.focus()
    expect(firstSwitch).toHaveFocus()

    const initialChecked = firstSwitch.getAttribute('aria-checked')

    // Toggle with Space
    await user.keyboard(' ')
    await waitFor(() => {
      expect(firstSwitch).toHaveAttribute(
        'aria-checked',
        initialChecked === 'true' ? 'false' : 'true'
      )
    })

    // Toggle with Enter
    const afterSpaceChecked = firstSwitch.getAttribute('aria-checked')
    await user.keyboard('{Enter}')
    await waitFor(() => {
      expect(firstSwitch).toHaveAttribute(
        'aria-checked',
        afterSpaceChecked === 'true' ? 'false' : 'true'
      )
    })
  })

  // -----------------------------------------------------------------------
  // 6. Empty state renders correctly
  // -----------------------------------------------------------------------
  it('renders empty state when no preferences are returned', async () => {
    server.use(
      http.get('/api/v1/users/:userId/notification-preferences', () => {
        return HttpResponse.json({
          data: [],
          page: { number: 0, size: 100, totalElements: 0, totalPages: 0 },
          links: { next: null, prev: null },
        })
      })
    )

    renderPage()

    await waitFor(() => {
      expect(
        screen.getByText(/no notification categories are configured/i)
      ).toBeInTheDocument()
    })

    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  // -----------------------------------------------------------------------
  // 7. Permission-denied state renders (no user details exposed)
  // -----------------------------------------------------------------------
  it('renders permission-denied state without exposing user details', async () => {
    server.use(
      http.get('/api/v1/users/:userId/notification-preferences', () => {
        return HttpResponse.json({ error: 'Forbidden' }, { status: 403 })
      })
    )

    renderPage()

    await waitFor(() => {
      expect(
        screen.getByText(/you don't have permission to view notification preferences/i)
      ).toBeInTheDocument()
    })

    // Should not mention the userId or reveal user existence
    expect(screen.queryByText(/user-123/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/does not exist/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/not found/i)).not.toBeInTheDocument()

    // No table should be rendered
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  // -----------------------------------------------------------------------
  // 8. Error state renders with traceId
  // -----------------------------------------------------------------------
  it('renders error state with traceId when server returns 500', async () => {
    server.use(
      http.get('/api/v1/users/:userId/notification-preferences', () => {
        return HttpResponse.json(
          { error: 'Internal Server Error' },
          {
            status: 500,
            headers: { 'X-Trace-Id': 'trace-abc-123' },
          }
        )
      })
    )

    renderPage()

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })

    // TraceId should be displayed
    expect(screen.getByText('trace-abc-123')).toBeInTheDocument()
  })

  // -----------------------------------------------------------------------
  // 9. Degraded state renders with staleness info
  // -----------------------------------------------------------------------
  it('renders degraded state banner when data is stale', async () => {
    // We need a QueryClient where staleTime is 0 so data is immediately stale
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: {
          retry: false,
          staleTime: 0,
          gcTime: Infinity,
        },
      },
    })

    // Pre-populate the cache with old data to simulate stale state
    queryClient.setQueryData(
      ['settings', 'notification-preferences', 'user-123'],
      {
        data: [
          { category: 'WORK_ORDER_CREATED', channel: 'EMAIL', enabled: true, source: 'DEFAULT' },
          { category: 'WORK_ORDER_CREATED', channel: 'SMS', enabled: false, source: 'DEFAULT' },
          { category: 'WORK_ORDER_CREATED', channel: 'IN_APP', enabled: true, source: 'DEFAULT' },
          { category: 'WORK_ORDER_CREATED', channel: 'PUSH', enabled: false, source: 'DEFAULT' },
        ],
        page: { number: 0, size: 100, totalElements: 4, totalPages: 1 },
        links: { next: null, prev: null },
      }
    )

    // Make the server delay its response so that isStale is true during rendering
    server.use(
      http.get('/api/v1/users/:userId/notification-preferences', async () => {
        // Delay to keep data stale for a moment
        await new Promise((resolve) => setTimeout(resolve, 100))
        return HttpResponse.json({
          data: [
            { category: 'WORK_ORDER_CREATED', channel: 'EMAIL', enabled: true, source: 'DEFAULT' },
            { category: 'WORK_ORDER_CREATED', channel: 'SMS', enabled: false, source: 'DEFAULT' },
            { category: 'WORK_ORDER_CREATED', channel: 'IN_APP', enabled: true, source: 'DEFAULT' },
            { category: 'WORK_ORDER_CREATED', channel: 'PUSH', enabled: false, source: 'DEFAULT' },
          ],
          page: { number: 0, size: 100, totalElements: 4, totalPages: 1 },
          links: { next: null, prev: null },
        })
      })
    )

    render(
      <QueryClientProvider client={queryClient}>
        <NotificationPreferencesPage userId="user-123" />
      </QueryClientProvider>
    )

    // The data should render immediately from cache (stale)
    await waitFor(() => {
      expect(screen.getByText('Work Order Created')).toBeInTheDocument()
    })

    // The degraded banner should appear since data is stale
    await waitFor(() => {
      // The staleness detail paragraph contains both "Notification preferences" and "last updated"
      expect(screen.getByText(/last updated/i)).toBeInTheDocument()
      // The banner message is specific to the degraded state
      expect(screen.getByText(/showing cached notification preferences/i)).toBeInTheDocument()
    })
  })

  // -----------------------------------------------------------------------
  // 10. Warning shows when all channels disabled for a category
  // -----------------------------------------------------------------------
  it('shows reduced reach warning when all channels are disabled for a category', async () => {
    server.use(
      http.get('/api/v1/users/:userId/notification-preferences', () => {
        return HttpResponse.json({
          data: [
            // WORK_ORDER_CREATED: all channels disabled
            { category: 'WORK_ORDER_CREATED', channel: 'EMAIL', enabled: false, source: 'EXPLICIT' },
            { category: 'WORK_ORDER_CREATED', channel: 'SMS', enabled: false, source: 'EXPLICIT' },
            { category: 'WORK_ORDER_CREATED', channel: 'IN_APP', enabled: false, source: 'EXPLICIT' },
            { category: 'WORK_ORDER_CREATED', channel: 'PUSH', enabled: false, source: 'EXPLICIT' },
            // SLA_AT_RISK: some channels enabled
            { category: 'SLA_AT_RISK', channel: 'EMAIL', enabled: true, source: 'EXPLICIT' },
            { category: 'SLA_AT_RISK', channel: 'SMS', enabled: false, source: 'DEFAULT' },
            { category: 'SLA_AT_RISK', channel: 'IN_APP', enabled: true, source: 'DEFAULT' },
            { category: 'SLA_AT_RISK', channel: 'PUSH', enabled: false, source: 'DEFAULT' },
          ],
          page: { number: 0, size: 100, totalElements: 8, totalPages: 1 },
          links: { next: null, prev: null },
        })
      })
    )

    renderPage()

    await waitFor(() => {
      expect(screen.queryByText(/loading/i)).not.toBeInTheDocument()
    })

    // The "reduced reach" warning should show for WORK_ORDER_CREATED
    expect(screen.getByText(/reduced reach/i)).toBeInTheDocument()

    // The Disabled chip should show for WORK_ORDER_CREATED
    const rows = screen.getAllByRole('row')
    // Find the WORK_ORDER_CREATED row
    const woCreatedRow = rows.find((row) => within(row).queryByText('Work Order Created'))
    expect(woCreatedRow).toBeDefined()
    if (woCreatedRow) {
      expect(within(woCreatedRow).getByText(/disabled/i)).toBeInTheDocument()
    }

    // SLA_AT_RISK should show Active chip, not the warning
    const slaRow = rows.find((row) => within(row).queryByText('SLA At Risk'))
    if (slaRow) {
      expect(within(slaRow).getByText(/active/i)).toBeInTheDocument()
      expect(within(slaRow).queryByText(/reduced reach/i)).not.toBeInTheDocument()
    }
  })
})
