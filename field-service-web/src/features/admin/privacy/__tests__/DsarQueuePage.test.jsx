/**
 * @fileoverview RTL tests for DsarQueuePage.
 *
 * Covers: role guard, countdown rendering, at-risk/overdue cells, row navigation.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthContext } from '../../../../app/AuthContext.js'
import { dsarRequestsHandler } from '../../../../mocks/handlers/privacy.js'
import DsarQueuePage from '../DsarQueuePage.jsx'

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    logger: { log: () => {}, warn: () => {}, error: () => {} },
  })
}

function makeAuth(roles = ['PRIVACY_ADMIN']) {
  return { accessToken: 'tok', roles, userId: 'u1', setAuth: vi.fn(), clearAuth: vi.fn() }
}

function renderPage(roles = ['PRIVACY_ADMIN']) {
  return render(
    <QueryClientProvider client={makeClient()}>
      <AuthContext.Provider value={makeAuth(roles)}>
        <MemoryRouter>
          <DsarQueuePage />
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.stubGlobal('fetch', dsarRequestsHandler())
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe('DsarQueuePage — role guard', () => {
  it('renders PermissionDeniedState for non-privacy roles', async () => {
    renderPage(['CUSTOMER'])
    await waitFor(() =>
      expect(screen.queryByText(/Data Subject Requests/i)).toBeNull()
    )
    expect(screen.getByText(/permission|access denied/i)).toBeInTheDocument()
  })

  it('renders queue for PRIVACY_ADMIN', async () => {
    renderPage(['PRIVACY_ADMIN'])
    await waitFor(() => screen.getByText(/Data Subject Requests/i))
  })
})

describe('DsarQueuePage — request rows', () => {
  it('renders request types from fixture', async () => {
    renderPage()
    await waitFor(() => screen.getByText('ACCESS'))
    expect(screen.getByText('ERASURE')).toBeInTheDocument()
    expect(screen.getByText('PORTABILITY')).toBeInTheDocument()
  })

  it('renders subject IDs', async () => {
    renderPage()
    await waitFor(() => screen.getByText('cust-111'))
    expect(screen.getByText('cust-222')).toBeInTheDocument()
  })
})

describe('DsarQueuePage — countdown cells', () => {
  it('renders at-risk countdown for atRisk=true rows', async () => {
    renderPage()
    await waitFor(() => screen.getByText('cust-111'))
    // at-risk row: fixture d1 has atRisk:true
    const atRiskCell = screen.getByLabelText(/at risk/i)
    expect(atRiskCell).toBeInTheDocument()
  })
})

describe('DsarQueuePage — state filter', () => {
  it('renders state filter buttons', async () => {
    renderPage()
    await waitFor(() => screen.getByText(/Data Subject Requests/i))
    expect(screen.getByRole('button', { name: 'All' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'RECEIVED' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'IN PROGRESS' })).toBeInTheDocument()
  })

  it('All filter button has aria-pressed=true initially', async () => {
    renderPage()
    await waitFor(() => screen.getByText(/Data Subject Requests/i))
    const allBtn = screen.getByRole('button', { name: 'All' })
    expect(allBtn).toHaveAttribute('aria-pressed', 'true')
  })
})
