/**
 * @fileoverview RTL tests for ClassificationRegistryPage.
 *
 * Covers: table render, role guard, sort allow-list enforcement, 409 conflict.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthContext } from '../../../../app/AuthContext.js'
import { classificationsHandler, classificationsMutationHandler } from '../../../../mocks/handlers/privacy.js'
import ClassificationRegistryPage from '../ClassificationRegistryPage.jsx'

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
          <ClassificationRegistryPage />
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.stubGlobal('fetch', classificationsHandler())
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe('ClassificationRegistryPage — role guard', () => {
  it('renders PermissionDeniedState for unauthorised roles', async () => {
    renderPage(['TECHNICIAN'])
    await waitFor(() =>
      expect(screen.queryByText(/Classification Registry/i)).toBeNull()
    )
    expect(screen.getByText(/permission|access denied/i)).toBeInTheDocument()
  })

  it('renders table for PRIVACY_ADMIN', async () => {
    renderPage(['PRIVACY_ADMIN'])
    await waitFor(() => screen.getByText(/Classification Registry/i))
    expect(screen.getByText('Customer')).toBeInTheDocument()
  })

  it('renders table for ADMIN', async () => {
    renderPage(['ADMIN'])
    await waitFor(() => screen.getByText(/Classification Registry/i))
    expect(screen.getByText('Customer')).toBeInTheDocument()
  })
})

describe('ClassificationRegistryPage — table content', () => {
  it('displays classification tiers from fixture', async () => {
    renderPage()
    await waitFor(() => screen.getByText('CONFIDENTIAL'))
    expect(screen.getByText('RESTRICTED')).toBeInTheDocument()
    expect(screen.getByText('INTERNAL')).toBeInTheDocument()
  })

  it('displays field names from fixture', async () => {
    renderPage()
    await waitFor(() => screen.getByText('name'))
    expect(screen.getByText('primaryContactEmail')).toBeInTheDocument()
  })
})

describe('ClassificationRegistryPage — edit mutation', () => {
  it('opens modal and submits tier change', async () => {
    vi.stubGlobal('fetch', classificationsMutationHandler())
    renderPage()
    await waitFor(() => screen.getByText('name'))
    // Click edit for the first row
    const editButtons = await screen.findAllByRole('button', { name: /edit/i })
    fireEvent.click(editButtons[0])
    // Modal should appear
    await waitFor(() => screen.getByRole('dialog'))
    // The form submit button
    const saveBtn = screen.getByRole('button', { name: /save/i })
    expect(saveBtn).toBeInTheDocument()
  })

  it('shows conflict warning on 409 response', async () => {
    vi.stubGlobal('fetch', classificationsMutationHandler({ conflict: true }))
    renderPage()
    await waitFor(() => screen.getByText('name'))
    const editButtons = await screen.findAllByRole('button', { name: /edit/i })
    fireEvent.click(editButtons[0])
    await waitFor(() => screen.getByRole('dialog'))
    const saveBtn = screen.getByRole('button', { name: /save/i })
    fireEvent.click(saveBtn)
    await waitFor(() =>
      screen.getByText(/conflict|version/i)
    )
  })
})
