/**
 * @fileoverview RTL tests for RetentionSchedulePage.
 *
 * Covers: ratification status rendering, dry-run trigger, "No data will be changed" message.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthContext } from '../../../../app/AuthContext.js'
import { retentionPoliciesHandler, retentionWithDryRunHandler } from '../../../../mocks/handlers/privacy.js'
import RetentionSchedulePage from '../RetentionSchedulePage.jsx'

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
          <RetentionSchedulePage />
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.stubGlobal('fetch', retentionPoliciesHandler())
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe('RetentionSchedulePage — role guard', () => {
  it('shows PermissionDeniedState for non-privacy roles', async () => {
    renderPage(['DISPATCHER'])
    await waitFor(() =>
      expect(screen.queryByText(/Retention Schedule/i)).toBeNull()
    )
    expect(screen.getByText(/permission|access denied/i)).toBeInTheDocument()
  })
})

describe('RetentionSchedulePage — table content', () => {
  it('renders ratified and unratified rows', async () => {
    renderPage()
    await waitFor(() => screen.getByText(/Retention Schedule/i))
    // Ratified rows
    expect(screen.getByText('CONTRACT')).toBeInTheDocument()
    // Unratified indicator
    expect(screen.getByText(/Indicative placeholder/i)).toBeInTheDocument()
  })

  it('displays entity names from fixture', async () => {
    renderPage()
    await waitFor(() => screen.getByText('WorkOrder'))
    expect(screen.getByText('WorkOrderNote')).toBeInTheDocument()
  })
})

describe('RetentionSchedulePage — dry-run', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', retentionWithDryRunHandler())
  })

  it('triggers dry-run and shows result panel', async () => {
    renderPage()
    await waitFor(() => screen.getByText('WorkOrder'))
    const dryRunButtons = await screen.findAllByRole('button', { name: /dry.?run/i })
    fireEvent.click(dryRunButtons[0])
    await waitFor(() =>
      screen.getByTestId('dry-run-result')
    )
    // Shows affected rows
    expect(screen.getByText(/142/)).toBeInTheDocument()
  })

  it('shows "No data will be changed" message after dry-run', async () => {
    renderPage()
    await waitFor(() => screen.getByText('WorkOrder'))
    const dryRunButtons = await screen.findAllByRole('button', { name: /dry.?run/i })
    fireEvent.click(dryRunButtons[0])
    await waitFor(() =>
      screen.getByText(/No data will be changed/i)
    )
  })
})
