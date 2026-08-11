import React from 'react';
import { RouterProvider } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AppearanceProvider from '../appearance/AppearanceProvider.jsx';
import { DensityProvider } from '../density/DensityContext.js';
import { ToastProvider } from '../components/index.js';
import { AuthProvider } from './AuthContext.js';
import { router } from './router.jsx';

/**
 * Single composition root for the entire application.
 *
 * Provider mount order (outermost → innermost):
 *   1. QueryClientProvider  — data layer; outermost so all other providers
 *                              can issue queries during their own setup
 *   2. AppearanceProvider   — reads DOM data-appearance set by the pre-paint
 *                              bootstrap in main.jsx; must wrap the router so
 *                              the appearance switch is available from every
 *                              surface (AC-6)
 *   3. DensityProvider      — persona density default; defaults to
 *                              'comfortable' until WO-091 defines variants
 *   4. ToastProvider        — toast region must be above the router so
 *                              route transitions do not unmount queued toasts
 *   5. AuthProvider         — holds the decoded in-memory access token;
 *                              wraps the router so any route can call useAuth()
 *   6. RouterProvider       — mounts the router; must be innermost so it can
 *                              consume all the contexts above
 *
 * IMPORTANT: Do not add providers outside this file. Later epics hang their
 * providers here so every screen inherits consistent context without
 * re-mounting providers inside individual surface routes.
 */

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 30_000,
    },
    mutations: {
      retry: 0,
    },
  },
});

export default function AppProviders() {
  return (
    <QueryClientProvider client={queryClient}>
      <AppearanceProvider>
        <DensityProvider>
          <ToastProvider>
            <AuthProvider>
              <RouterProvider router={router} />
            </AuthProvider>
          </ToastProvider>
        </DensityProvider>
      </AppearanceProvider>
    </QueryClientProvider>
  );
}
