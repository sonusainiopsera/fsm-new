/**
 * @fileoverview RTL integration tests for SignInPage.
 *
 * The sign-in page is tested through the surface re-export to verify the
 * routing integration stays intact.
 *
 * The tests stub `fetch` via vi.stubGlobal so we exercise the full mutation
 * path through useSignIn without a real network.  tokenStore is reset between
 * tests to prevent state bleed.
 *
 * @testing-library/user-event is not installed; tests use fireEvent.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import { AppearanceProvider } from '../../../appearance/AppearanceProvider.jsx'
import { AuthContext } from '../../../app/AuthContext.js'
import { _resetForTesting } from '../../../api/tokenStore.js'
import { authHandlers, AUTH_FIXTURES, AUTH_REFRESH_URL } from '../../../mocks/handlers/auth.js'
import SignInPage from '../SignInPage.jsx'

// ── Test scaffolding ──────────────────────────────────────────────────────────

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
    logger: { log: () => {}, warn: () => {}, error: () => {} },
  })
}

function renderSignIn({ authOverrides = {} } = {}) {
  const setAuth = vi.fn()
  const clearAuth = vi.fn()
  const authState = {
    accessToken: null,
    roles: [],
    userId: null,
    storedPreference: null,
    setAuth,
    clearAuth,
    ...authOverrides,
  }
  const client = makeClient()
  const utils = render(
    <QueryClientProvider client={client}>
      <AuthContext.Provider value={authState}>
        <AppearanceProvider>
          <MemoryRouter initialEntries={['/sign-in']}>
            <SignInPage />
          </MemoryRouter>
        </AppearanceProvider>
      </AuthContext.Provider>
    </QueryClientProvider>,
  )
  return { ...utils, setAuth, clearAuth }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

function stubRefreshTo401() {
  vi.stubGlobal('fetch', async (url) => {
    if (url === AUTH_REFRESH_URL) {
      return new Response(JSON.stringify(AUTH_FIXTURES.refresh401.body), {
        status: 401,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    return new Response('{}', { status: 404 })
  })
}

function typeCredentials(email = 'user@example.com', password = 'password123') {
  fireEvent.change(screen.getByRole('textbox', { name: /email/i }), {
    target: { value: email },
  })
  // Password input id is sign-in-password; query by label text
  const passwordInput = document.getElementById('sign-in-password')
  fireEvent.change(passwordInput, { target: { value: password } })
}

// ── Setup/teardown ────────────────────────────────────────────────────────────

beforeEach(() => {
  _resetForTesting()
  // Boot-time refresh will fail so the sign-in form shows
  stubRefreshTo401()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('SignInPage — initial state', () => {
  it('shows loading skeleton during boot refresh, then form', async () => {
    // Delay refresh to observe loading state
    vi.stubGlobal('fetch', async (url) => {
      if (url === AUTH_REFRESH_URL) {
        await new Promise(r => setTimeout(r, 10))
        return new Response(JSON.stringify(AUTH_FIXTURES.refresh401.body), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      return new Response('{}', { status: 404 })
    })

    renderSignIn()
    // Loading shown first
    expect(screen.getByTestId('sign-in-boot-loading')).toBeInTheDocument()

    // After refresh resolves, form is shown
    await waitFor(() => expect(screen.getByTestId('sign-in-page')).toBeInTheDocument())
    expect(screen.queryByTestId('sign-in-boot-loading')).not.toBeInTheDocument()
  })

  it('renders email input, password input, and sign-in button', async () => {
    renderSignIn()
    await waitFor(() => screen.getByRole('button', { name: /sign in/i }))

    expect(screen.getByRole('textbox', { name: /email/i })).toBeInTheDocument()
    expect(document.getElementById('sign-in-password')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /sign in/i })).toBeInTheDocument()
  })
})

describe('SignInPage — boot-time silent refresh success (AC-5)', () => {
  it('navigates away without showing the form when refresh succeeds', async () => {
    vi.stubGlobal('fetch', authHandlers.refreshSuccess())
    const { setAuth } = renderSignIn()

    await waitFor(() => expect(setAuth).toHaveBeenCalled())
    // Form should not be rendered after successful boot refresh
    expect(screen.queryByRole('button', { name: /sign in/i })).not.toBeInTheDocument()
  })
})

describe('SignInPage — successful login (AC-2, AC-4)', () => {
  it('calls setAuth with token and roles, shows no error alert', async () => {
    const { setAuth } = renderSignIn()
    await waitFor(() => screen.getByRole('button', { name: /sign in/i }))

    vi.stubGlobal('fetch', authHandlers.loginSuccess())

    typeCredentials()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /sign in/i }))
    })

    await waitFor(() => expect(setAuth).toHaveBeenCalledWith(
      expect.objectContaining({
        accessToken: expect.any(String),
        roles: ['TECHNICIAN'],
        userId: 'user-1',
      }),
    ))
    expect(screen.queryByTestId('generic-error-alert')).not.toBeInTheDocument()
  })

  it('does not write the access token to localStorage or sessionStorage (AC-3)', async () => {
    localStorage.clear()
    sessionStorage.clear()

    renderSignIn()
    await waitFor(() => screen.getByRole('button', { name: /sign in/i }))

    vi.stubGlobal('fetch', authHandlers.loginSuccess())

    typeCredentials()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /sign in/i }))
    })

    // Wait for mutation to complete
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /signing in/i })).not.toBeInTheDocument(),
    )

    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })
})

describe('SignInPage — 401 error (AC-6)', () => {
  it('shows API error message verbatim, does not infer account existence', async () => {
    renderSignIn()
    await waitFor(() => screen.getByRole('button', { name: /sign in/i }))

    vi.stubGlobal('fetch', authHandlers.login401())

    typeCredentials()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /sign in/i }))
    })

    await waitFor(() => screen.getByTestId('generic-error-alert'))
    const alert = screen.getByTestId('generic-error-alert')
    expect(alert).toHaveTextContent(AUTH_FIXTURES.login401.body.message)
    // Must not suggest the account does not exist
    expect(alert.textContent).not.toMatch(/no account/i)
  })
})

describe('SignInPage — 400 field errors (AC-7)', () => {
  it('shows per-field error messages for email and password fields', async () => {
    renderSignIn()
    await waitFor(() => screen.getByRole('button', { name: /sign in/i }))

    vi.stubGlobal('fetch', authHandlers.login400())

    typeCredentials()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /sign in/i }))
    })

    await waitFor(() =>
      screen.getByText(/must be a valid email address/i),
    )
    expect(screen.getByText(/must not be blank/i)).toBeInTheDocument()
    // Generic alert should not appear for field errors
    expect(screen.queryByTestId('generic-error-alert')).not.toBeInTheDocument()
  })
})

describe('SignInPage — 429 rate limit (AC-7)', () => {
  it('shows rate-limit message with retry-after seconds', async () => {
    renderSignIn()
    await waitFor(() => screen.getByRole('button', { name: /sign in/i }))

    vi.stubGlobal('fetch', authHandlers.login429())

    typeCredentials()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /sign in/i }))
    })

    await waitFor(() => screen.getByTestId('generic-error-alert'))
    const alert = screen.getByTestId('generic-error-alert')
    expect(alert).toHaveTextContent(/too many sign-in attempts/i)
    expect(alert).toHaveTextContent(/60 second/i)
  })
})

describe('SignInPage — 503 / network failure (AC-7)', () => {
  it('shows unavailability message for 503', async () => {
    renderSignIn()
    await waitFor(() => screen.getByRole('button', { name: /sign in/i }))

    vi.stubGlobal('fetch', authHandlers.login503())

    typeCredentials()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /sign in/i }))
    })

    await waitFor(() => screen.getByTestId('generic-error-alert'))
    const alert = screen.getByTestId('generic-error-alert')
    expect(alert).toHaveTextContent(/temporarily unavailable/i)
  })

  it('shows retry message on network failure', async () => {
    renderSignIn()
    await waitFor(() => screen.getByRole('button', { name: /sign in/i }))

    vi.stubGlobal('fetch', authHandlers.loginNetworkError())

    typeCredentials()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /sign in/i }))
    })

    await waitFor(() => screen.getByTestId('generic-error-alert'))
    expect(screen.getByTestId('generic-error-alert')).toBeInTheDocument()
  })
})

describe('SignInPage — double-submit prevention (AC-8)', () => {
  it('button is disabled while isPending and labelled "Signing in…"', async () => {
    renderSignIn()
    await waitFor(() => screen.getByRole('button', { name: /sign in/i }))

    // Use a never-resolving fetch to hold the pending state
    vi.stubGlobal('fetch', () => new Promise(() => {}))

    typeCredentials()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /sign in/i }))
    })

    await waitFor(() =>
      expect(screen.getByRole('button', { name: /signing in/i })).toBeDisabled(),
    )
  })
})

describe('SignInPage — error dismissal (AC-9)', () => {
  it('dismisses the error alert when the dismiss button is clicked', async () => {
    renderSignIn()
    await waitFor(() => screen.getByRole('button', { name: /sign in/i }))

    vi.stubGlobal('fetch', authHandlers.login401())

    typeCredentials()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /sign in/i }))
    })

    await waitFor(() => screen.getByTestId('generic-error-alert'))
    fireEvent.click(screen.getByRole('button', { name: /dismiss/i }))

    await waitFor(() =>
      expect(screen.queryByTestId('generic-error-alert')).not.toBeInTheDocument(),
    )
  })
})

describe('SignInPage — password show/hide toggle (AC-9)', () => {
  it('toggles the password input type when the Show button is clicked', async () => {
    renderSignIn()
    await waitFor(() => screen.getByRole('button', { name: /sign in/i }))

    const passwordInput = document.getElementById('sign-in-password')
    expect(passwordInput.type).toBe('password')

    fireEvent.click(screen.getByRole('button', { name: /show password/i }))
    expect(passwordInput.type).toBe('text')

    fireEvent.click(screen.getByRole('button', { name: /hide password/i }))
    expect(passwordInput.type).toBe('password')
  })
})

describe('SignInPage — appearance toggle (AC-9)', () => {
  it('renders the appearance toggle button', async () => {
    renderSignIn()
    await waitFor(() => screen.getByTestId('appearance-toggle'))
    expect(screen.getByTestId('appearance-toggle')).toBeInTheDocument()
  })
})
