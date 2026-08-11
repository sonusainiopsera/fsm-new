/**
 * @fileoverview Single composition root for all global providers.
 *
 * Provider order (outer → inner):
 * 1. QueryClientProvider  — TanStack Query client for all data fetching
 * 2. AuthContext.Provider — in-memory access token, roles, userId
 * 3. AppearanceProvider   — data-appearance attribute, localStorage mirror
 * 4. DensityProvider      — comfortable / compact density for tables / forms
 * 5. ToastProvider        — global toast queue (polite + assertive live regions)
 * 6. AppRouter            — React Router 6 + AppShell frame
 *
 * RULE: Every later epic mounts inside the router's <Outlet> — never re-mount
 * any of these providers inside a surface route. Doing so would tear down and
 * re-create the QueryClient (losing cache) or the ToastProvider (dropping
 * queued notifications).
 *
 * Density persona defaults (AC-6 note): the shell selects a persona default
 * from the active surface. WO-091 defines the concrete variant values. Until
 * then, 'comfortable' is the initial default for all surfaces.
 */
import { useState } from 'react'
import { QueryClientProvider } from '@tanstack/react-query'
import { AppearanceProvider } from '../appearance/AppearanceProvider.jsx'
import { DensityProvider } from '../density/DensityContext.js'
import { ToastProvider } from '../components/Toast/ToastProvider.jsx'
import { AuthContext } from './AuthContext.js'
import { AppRouter } from './router.jsx'
import { queryClient } from '../api/queryClient.js'

const INITIAL_AUTH = {
  accessToken: null,
  roles: [],
  userId: null,
  storedPreference: null,
}

export function AppProviders() {
  const [auth, setAuthState] = useState(INITIAL_AUTH)

  function setAuth(next) {
    setAuthState(prev => ({ ...prev, ...next }))
  }

  function clearAuth() {
    setAuthState(INITIAL_AUTH)
  }

  // serverPreference is derived from auth state once the user is signed in.
  // AppearanceProvider reads the localStorage mirror before the first fetch.
  const serverPreference = auth.accessToken ? (auth.storedPreference ?? null) : undefined

  return (
    <QueryClientProvider client={queryClient}>
      <AuthContext.Provider value={{ ...auth, setAuth, clearAuth }}>
        <AppearanceProvider serverPreference={serverPreference}>
          <DensityProvider>
            <ToastProvider>
              <AppRouter />
            </ToastProvider>
          </DensityProvider>
        </AppearanceProvider>
      </AuthContext.Provider>
    </QueryClientProvider>
  )
}
