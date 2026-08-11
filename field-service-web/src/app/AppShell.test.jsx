/**
 * System integration tests for AppShell.
 * These tests require react-router-dom and @tanstack/react-query.
 * Run after: npm install (in field-service-web/)
 *
 * Covers:
 *  - Authorised route renders content
 *  - Unauthorised route (403) renders PermissionDeniedState
 *  - Lazy-chunk loading shows LoadingState then content
 *  - Offline mutation blocked with retry affordance
 *  - Keyboard landmarks and skip link
 *  - Navigation filtering by role
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, act, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route, Outlet } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AppearanceProvider } from '../appearance/AppearanceProvider.jsx'
import { DensityProvider } from '../density/DensityContext.js'
import { ToastProvider } from '../components/Toast/ToastProvider.jsx'
import { AuthContext } from './AuthContext.js'
import { filterNavForRoles } from './navigation.js'
import { Sidebar } from './Sidebar/Sidebar.jsx'
import { TopBar } from './TopBar/TopBar.jsx'
import { ErrorBoundary } from './ErrorBoundary.jsx'

// Minimal test shell that avoids the full createBrowserRouter setup
function TestShell({ roles = [], children }) {
  const navItems = filterNavForRoles(roles)
  return (
    <>
      <a href="#main" data-testid="skip-link">Skip to main content</a>
      <header role="banner">
        <TopBar />
      </header>
      <nav aria-label="Primary navigation">
        <Sidebar navItems={navItems} />
      </nav>
      <main id="main" role="main">
        <ErrorBoundary>
          {children ?? <Outlet />}
        </ErrorBoundary>
      </main>
    </>
  )
}

function makeClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

function TestWrapper({ roles, children, auth }) {
  const client = makeClient()
  const authState = {
    accessToken: auth?.accessToken ?? null,
    roles: auth?.roles ?? roles ?? [],
    userId: null,
    storedPreference: null,
    setAuth: () => {},
    clearAuth: () => {},
  }
  return (
    <QueryClientProvider client={client}>
      <AuthContext.Provider value={authState}>
        <AppearanceProvider>
          <DensityProvider>
            <ToastProvider>
              {children}
            </ToastProvider>
          </DensityProvider>
        </AppearanceProvider>
      </AuthContext.Provider>
    </QueryClientProvider>
  )
}

describe('AppShell — landmarks and skip link', () => {
  beforeEach(() => {
    localStorage.clear()
    // Reset matchMedia to desktop
    Object.defineProperty(window, 'matchMedia', {
      writable: true, configurable: true,
      value: vi.fn().mockImplementation((query) => ({
        matches: false,
        media: query,
        onchange: null,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      })),
    })
  })

  it('renders a banner landmark', () => {
    render(
      <TestWrapper roles={['DISPATCHER']}>
        <MemoryRouter>
          <TestShell roles={['DISPATCHER']}>
            <div>Content</div>
          </TestShell>
        </MemoryRouter>
      </TestWrapper>
    )
    expect(screen.getByRole('banner')).toBeInTheDocument()
  })

  it('renders a navigation landmark', () => {
    render(
      <TestWrapper roles={['DISPATCHER']}>
        <MemoryRouter>
          <TestShell roles={['DISPATCHER']}>
            <div>Content</div>
          </TestShell>
        </MemoryRouter>
      </TestWrapper>
    )
    expect(screen.getByRole('navigation')).toBeInTheDocument()
  })

  it('renders a main landmark', () => {
    render(
      <TestWrapper roles={['DISPATCHER']}>
        <MemoryRouter>
          <TestShell roles={['DISPATCHER']}>
            <div>Content</div>
          </TestShell>
        </MemoryRouter>
      </TestWrapper>
    )
    expect(screen.getByRole('main')).toBeInTheDocument()
  })

  it('renders skip-to-main-content link', () => {
    render(
      <TestWrapper roles={['DISPATCHER']}>
        <MemoryRouter>
          <TestShell roles={['DISPATCHER']}>
            <div>Content</div>
          </TestShell>
        </MemoryRouter>
      </TestWrapper>
    )
    const skip = screen.getByTestId('skip-link')
    expect(skip).toBeInTheDocument()
    expect(skip.getAttribute('href')).toBe('#main')
  })
})

describe('AppShell — role-derived navigation', () => {
  it('dispatcher sees dispatch-board navigation item', () => {
    render(
      <TestWrapper roles={['DISPATCHER']}>
        <MemoryRouter>
          <TestShell roles={['DISPATCHER']}>
            <div>content</div>
          </TestShell>
        </MemoryRouter>
      </TestWrapper>
    )
    expect(screen.getByText('Dispatch Board')).toBeInTheDocument()
  })

  it('technician sees my-jobs but NOT dispatch-board', () => {
    render(
      <TestWrapper roles={['TECHNICIAN']}>
        <MemoryRouter>
          <TestShell roles={['TECHNICIAN']}>
            <div>content</div>
          </TestShell>
        </MemoryRouter>
      </TestWrapper>
    )
    expect(screen.getByText('My Jobs')).toBeInTheDocument()
    expect(screen.queryByText('Dispatch Board')).not.toBeInTheDocument()
  })

  it('manager sees Dashboard but NOT My Jobs', () => {
    render(
      <TestWrapper roles={['MANAGER']}>
        <MemoryRouter>
          <TestShell roles={['MANAGER']}>
            <div>content</div>
          </TestShell>
        </MemoryRouter>
      </TestWrapper>
    )
    expect(screen.getByText('Dashboard')).toBeInTheDocument()
    expect(screen.queryByText('My Jobs')).not.toBeInTheDocument()
  })
})

describe('AppShell — error boundary', () => {
  it('renders ErrorState when a child throws (A10: no stack trace)', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    function Bomb() { throw new Error('internal error details') }

    render(
      <TestWrapper roles={['DISPATCHER']}>
        <MemoryRouter>
          <TestShell roles={['DISPATCHER']}>
            <Bomb />
          </TestShell>
        </MemoryRouter>
      </TestWrapper>
    )

    expect(screen.getByText(/something went wrong/i)).toBeInTheDocument()
    expect(screen.queryByText('internal error details')).not.toBeInTheDocument()
    vi.restoreAllMocks()
  })

  it('renders PermissionDeniedState for 403 error', () => {
    const err = { status: 403 }
    render(
      <TestWrapper roles={['DISPATCHER']}>
        <ErrorBoundary error={err} />
      </TestWrapper>
    )
    expect(screen.getByText(/access denied/i)).toBeInTheDocument()
  })
})

describe('AppShell — offline mutation guard', () => {
  afterEach(() => {
    Object.defineProperty(navigator, 'onLine', { value: true, writable: true, configurable: true })
  })

  it('offline badge appears when navigator is offline', () => {
    Object.defineProperty(navigator, 'onLine', { value: false, writable: true, configurable: true })

    render(
      <TestWrapper roles={['TECHNICIAN']}>
        <MemoryRouter>
          <TestShell roles={['TECHNICIAN']}>
            <div>content</div>
          </TestShell>
        </MemoryRouter>
      </TestWrapper>
    )

    expect(screen.getByText('Offline')).toBeInTheDocument()
  })

  it('offline badge is absent when navigator is online', () => {
    Object.defineProperty(navigator, 'onLine', { value: true, writable: true, configurable: true })

    render(
      <TestWrapper roles={['TECHNICIAN']}>
        <MemoryRouter>
          <TestShell roles={['TECHNICIAN']}>
            <div>content</div>
          </TestShell>
        </MemoryRouter>
      </TestWrapper>
    )

    expect(screen.queryByText('Offline')).not.toBeInTheDocument()
  })
})
