/**
 * @fileoverview Integration tests for the privacy admin console.
 *
 * Covers end-to-end flows: PRIVACY_ADMIN sees all pages, DSAR detail navigation,
 * erasure confirmation flow, export disabled when unverified, unauthorized role check.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthContext } from '../../../../app/AuthContext.js'
import { fullPrivacyHandler, dsarDetailHandler } from '../../../../mocks/handlers/privacy.js'
import ClassificationRegistryPage from '../ClassificationRegistryPage.jsx'
import RetentionSchedulePage from '../RetentionSchedulePage.jsx'
import DsarQueuePage from '../DsarQueuePage.jsx'
import DsarRequestDetailPage from '../DsarRequestDetailPage.jsx'

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    logger: { log: () => {}, warn: () => {}, error: () => {} },
  })
}

function makeAuth(roles = ['PRIVACY_ADMIN']) {
  return { accessToken: 'tok', roles, userId: 'u1', setAuth: vi.fn(), clearAuth: vi.fn() }
}

function renderWithRouter(element, { initialEntries = ['/'], roles = ['PRIVACY_ADMIN'] } = {}) {
  return render(
    <QueryClientProvider client={makeClient()}>
      <AuthContext.Provider value={makeAuth(roles)}>
        <MemoryRouter initialEntries={initialEntries}>
          {element}
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.stubGlobal('fetch', fullPrivacyHandler())
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe('Privacy admin — PRIVACY_ADMIN access', () => {
  it('ClassificationRegistryPage renders for PRIVACY_ADMIN', async () => {
    renderWithRouter(<ClassificationRegistryPage />)
    await waitFor(() => screen.getByText(/Classification Registry/i))
    expect(screen.getByText('CONFIDENTIAL')).toBeInTheDocument()
  })

  it('RetentionSchedulePage renders for PRIVACY_ADMIN', async () => {
    renderWithRouter(<RetentionSchedulePage />)
    await waitFor(() => screen.getByText(/Retention Schedule/i))
    expect(screen.getByText('WorkOrder')).toBeInTheDocument()
  })

  it('DsarQueuePage renders for PRIVACY_ADMIN', async () => {
    renderWithRouter(<DsarQueuePage />)
    await waitFor(() => screen.getByText(/Data Subject Requests/i))
    expect(screen.getByText('ACCESS')).toBeInTheDocument()
  })
})

describe('Privacy admin — ADMIN access', () => {
  it('ClassificationRegistryPage renders for ADMIN', async () => {
    renderWithRouter(<ClassificationRegistryPage />, { roles: ['ADMIN'] })
    await waitFor(() => screen.getByText(/Classification Registry/i))
  })
})

describe('Privacy admin — unauthorized role', () => {
  it('ClassificationRegistryPage shows PermissionDeniedState for MANAGER', async () => {
    renderWithRouter(<ClassificationRegistryPage />, { roles: ['MANAGER'] })
    await waitFor(() =>
      expect(screen.queryByText(/Classification Registry/i)).toBeNull()
    )
    expect(screen.getByText(/permission|access denied/i)).toBeInTheDocument()
  })

  it('DsarQueuePage shows PermissionDeniedState for TECHNICIAN', async () => {
    renderWithRouter(<DsarQueuePage />, { roles: ['TECHNICIAN'] })
    await waitFor(() =>
      expect(screen.queryByText(/Data Subject Requests/i)).toBeNull()
    )
    expect(screen.getByText(/permission|access denied/i)).toBeInTheDocument()
  })
})

describe('DSAR detail — export disabled when unverified', () => {
  it('shows identity-not-verified message when export manifest exists', async () => {
    // Use detail fixture with no identityVerification
    const unverifiedDetail = {
      id: 'd1000000-0000-7000-8000-000000000001',
      requestType: 'ACCESS',
      subjectType: 'CUSTOMER',
      subjectId: 'cust-111',
      state: 'RECEIVED',
      submittedAt: '2026-07-14T09:00:00Z',
      dueAt: '2026-08-13T09:00:00Z',
      atRisk: false,
      identityVerification: null,
      stateHistory: [],
      exportManifest: {
        generatedAt: '2026-07-15T12:00:00Z',
        sections: [{ name: 'Profile', rowCount: 1 }],
      },
    }
    vi.stubGlobal('fetch', (url) => {
      if (url.match(/dsar-requests\/[^/]+$/)) {
        return Promise.resolve({
          ok: true, status: 200,
          headers: { get: (h) => h.toLowerCase() === 'content-type' ? 'application/json' : null },
          json: () => Promise.resolve(unverifiedDetail),
          text: () => Promise.resolve(JSON.stringify(unverifiedDetail)),
        })
      }
      return Promise.reject(new Error(`Unhandled: ${url}`))
    })

    renderWithRouter(
      <Routes>
        <Route path="/dsar/:id" element={<DsarRequestDetailPage />} />
      </Routes>,
      { initialEntries: ['/dsar/d1000000-0000-7000-8000-000000000001'] },
    )

    await waitFor(() => screen.getByText('ACCESS'))
    expect(screen.getByText(/identity.*not.*verified|not.*verified/i)).toBeInTheDocument()
    // Download button should be absent
    expect(screen.queryByRole('button', { name: /download/i })).toBeNull()
  })
})

describe('DSAR detail — erasure flow', () => {
  it('shows erasure section for ERASURE type non-terminal DSAR', async () => {
    const erasureDsar = {
      id: 'd1000000-0000-7000-8000-000000000002',
      requestType: 'ERASURE',
      subjectType: 'CUSTOMER',
      subjectId: 'cust-222',
      state: 'VERIFIED',
      submittedAt: '2026-07-01T14:30:00Z',
      dueAt: '2026-08-31T14:30:00Z',
      atRisk: false,
      identityVerification: { verifiedAt: '2026-07-02T10:00:00Z', method: 'DOCUMENT_CHECK' },
      stateHistory: [],
      exportManifest: null,
    }
    vi.stubGlobal('fetch', (url) => {
      if (url.match(/dsar-requests\/[^/]+$/)) {
        return Promise.resolve({
          ok: true, status: 200,
          headers: { get: (h) => h.toLowerCase() === 'content-type' ? 'application/json' : null },
          json: () => Promise.resolve(erasureDsar),
          text: () => Promise.resolve(JSON.stringify(erasureDsar)),
        })
      }
      return Promise.reject(new Error(`Unhandled: ${url}`))
    })

    renderWithRouter(
      <Routes>
        <Route path="/dsar/:id" element={<DsarRequestDetailPage />} />
      </Routes>,
      { initialEntries: ['/dsar/d1000000-0000-7000-8000-000000000002'] },
    )

    await waitFor(() => screen.getByText('ERASURE'))
    expect(screen.getByRole('button', { name: /initiate erasure/i })).toBeInTheDocument()
  })

  it('erasure section absent for terminal DSAR state', async () => {
    const terminalDsar = {
      id: 'd1000000-0000-7000-8000-000000000004',
      requestType: 'ERASURE',
      subjectType: 'CUSTOMER',
      subjectId: 'cust-444',
      state: 'FULFILLED',
      submittedAt: '2026-06-01T10:00:00Z',
      dueAt: '2026-07-01T10:00:00Z',
      atRisk: false,
      identityVerification: { verifiedAt: '2026-06-02T10:00:00Z', method: 'DOCUMENT_CHECK' },
      stateHistory: [],
      exportManifest: null,
    }
    vi.stubGlobal('fetch', (url) => {
      if (url.match(/dsar-requests\/[^/]+$/)) {
        return Promise.resolve({
          ok: true, status: 200,
          headers: { get: (h) => h.toLowerCase() === 'content-type' ? 'application/json' : null },
          json: () => Promise.resolve(terminalDsar),
          text: () => Promise.resolve(JSON.stringify(terminalDsar)),
        })
      }
      return Promise.reject(new Error(`Unhandled: ${url}`))
    })

    renderWithRouter(
      <Routes>
        <Route path="/dsar/:id" element={<DsarRequestDetailPage />} />
      </Routes>,
      { initialEntries: ['/dsar/d1000000-0000-7000-8000-000000000004'] },
    )

    await waitFor(() => screen.getByText('FULFILLED'))
    expect(screen.queryByRole('button', { name: /initiate erasure/i })).toBeNull()
  })
})
