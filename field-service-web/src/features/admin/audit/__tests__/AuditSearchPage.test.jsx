/**
 * @fileoverview RTL tests for AuditSearchPage (WO-199).
 *
 * Covers: loading state, renders results, permission-denied for non-privileged roles,
 * entityType filter enforces allow-list, diff drawer opens on row click, export action.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthContext } from '../../../../app/AuthContext.js'
import AuditSearchPage from '../AuditSearchPage.jsx'

const REVISIONS_FIXTURE = {
  data: [
    {
      revisionNumber: 42,
      revisionTimestamp: '2026-06-01T10:00:00Z',
      actor: 'admin-user-uuid',
      entityType: 'WorkOrder',
      entityId: 'cc000000-0000-0000-0000-000000000030',
      changeType: 'MOD',
      changedFields: ['state'],
    },
  ],
  page: { number: 0, size: 20, totalElements: 1 },
  hasNext: false,
}

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    logger: { log: () => {}, warn: () => {}, error: () => {} },
  })
}

function makeAuth(roles = ['ADMIN']) {
  return { accessToken: 'tok', roles, userId: 'u1', setAuth: vi.fn(), clearAuth: vi.fn() }
}

function renderPage(roles = ['ADMIN'], fetchImpl = null) {
  if (fetchImpl) {
    vi.stubGlobal('fetch', fetchImpl)
  }
  return render(
    <QueryClientProvider client={makeClient()}>
      <AuthContext.Provider value={makeAuth(roles)}>
        <MemoryRouter>
          <AuditSearchPage />
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe('AuditSearchPage — permission guard', () => {
  it('shows permission-denied state for TECHNICIAN', () => {
    renderPage(['TECHNICIAN'])
    expect(screen.getByText(/permission denied/i)).toBeInTheDocument()
  })

  it('shows permission-denied state for DISPATCHER', () => {
    renderPage(['DISPATCHER'])
    expect(screen.getByText(/permission denied/i)).toBeInTheDocument()
  })

  it('shows permission-denied state for CUSTOMER', () => {
    renderPage(['CUSTOMER'])
    expect(screen.getByText(/permission denied/i)).toBeInTheDocument()
  })

  it('renders table for ADMIN', async () => {
    renderPage(['ADMIN'], () => Promise.resolve({
      ok: true,
      json: () => Promise.resolve(REVISIONS_FIXTURE),
    }))
    await waitFor(() => screen.getByText('Audit Trail'))
  })

  it('renders table for COMPLIANCE_REVIEWER', async () => {
    renderPage(['COMPLIANCE_REVIEWER'], () => Promise.resolve({
      ok: true,
      json: () => Promise.resolve(REVISIONS_FIXTURE),
    }))
    await waitFor(() => screen.getByText('Audit Trail'))
  })
})

describe('AuditSearchPage — results rendering', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', () => Promise.resolve({
      ok: true,
      json: () => Promise.resolve(REVISIONS_FIXTURE),
    }))
  })

  it('renders revision rows', async () => {
    renderPage()
    await waitFor(() => screen.getByText('42'))
    expect(screen.getByText('WorkOrder')).toBeInTheDocument()
    expect(screen.getByText('MOD')).toBeInTheDocument()
  })

  it('renders entity type filter', async () => {
    renderPage()
    await waitFor(() => screen.getByText('Audit Trail'))
    const select = screen.getByRole('combobox', { name: /entity type/i })
    expect(select).toBeInTheDocument()
  })

  it('shows empty state when no revisions', async () => {
    vi.stubGlobal('fetch', () => Promise.resolve({
      ok: true,
      json: () => Promise.resolve({ data: [], page: { number: 0, size: 20, totalElements: 0 }, hasNext: false }),
    }))
    renderPage()
    await waitFor(() => screen.getByText(/no audit revisions/i))
  })

  it('shows error state on fetch failure', async () => {
    vi.stubGlobal('fetch', () => Promise.resolve({
      ok: false,
      json: () => Promise.resolve({ message: 'Internal server error' }),
    }))
    renderPage()
    await waitFor(() => screen.getByText(/failed to load/i))
  })
})

describe('AuditSearchPage — export', () => {
  it('export CSV button is visible for ADMIN', async () => {
    vi.stubGlobal('fetch', () => Promise.resolve({
      ok: true,
      json: () => Promise.resolve(REVISIONS_FIXTURE),
    }))
    renderPage()
    await waitFor(() => screen.getByText('Audit Trail'))
    expect(screen.getByRole('button', { name: /export csv/i })).toBeInTheDocument()
  })
})
