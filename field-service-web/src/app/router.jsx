/**
 * @fileoverview React Router 6 data router configuration.
 *
 * Each surface route group is lazily loaded (React.lazy + Suspense) so a
 * technician on a mobile connection never downloads the dispatcher board or
 * the operations charting bundle. The field entry is kept deliberately small.
 *
 * AC-4 authorisation: Protected routes rely on server 403 responses mapped
 * by RouteErrorBoundary → PermissionDeniedState. The client never grants
 * access on the roles claim alone.
 */
import { lazy, Suspense } from 'react'
import { createBrowserRouter, RouterProvider, Navigate, useRouteError } from 'react-router-dom'
import { AppShell } from './AppShell.jsx'
import { ErrorBoundary } from './ErrorBoundary.jsx'
import { LoadingState } from '../components/index.js'

// Route-level code splitting — four surface chunks + sign-in + not-found
const DispatchSurface = lazy(() => import('../surfaces/dispatch/index.jsx'))
const FieldSurface = lazy(() => import('../surfaces/field/index.jsx'))
const OperationsSurface = lazy(() => import('../surfaces/operations/index.jsx'))
const PortalSurface = lazy(() => import('../surfaces/portal/index.jsx'))
const SignIn = lazy(() => import('../surfaces/auth/SignIn.jsx'))
const NotFound = lazy(() => import('../surfaces/NotFound.jsx'))

function SuspenseFallback() {
  return <LoadingState />
}

function LazyRoute({ children }) {
  return (
    <Suspense fallback={<SuspenseFallback />}>
      {children}
    </Suspense>
  )
}

function RouteErrorPage() {
  const error = useRouteError()
  return <ErrorBoundary error={error} />
}

export const router = createBrowserRouter([
  {
    path: '/sign-in',
    element: (
      <LazyRoute>
        <SignIn />
      </LazyRoute>
    ),
  },
  {
    path: '/',
    element: <AppShell />,
    errorElement: <RouteErrorPage />,
    children: [
      {
        index: true,
        element: <Navigate to="/dispatch" replace />,
      },
      {
        path: 'dispatch/*',
        element: (
          <LazyRoute>
            <DispatchSurface />
          </LazyRoute>
        ),
      },
      {
        path: 'field/*',
        element: (
          <LazyRoute>
            <FieldSurface />
          </LazyRoute>
        ),
      },
      {
        path: 'operations/*',
        element: (
          <LazyRoute>
            <OperationsSurface />
          </LazyRoute>
        ),
      },
      {
        path: 'portal/*',
        element: (
          <LazyRoute>
            <PortalSurface />
          </LazyRoute>
        ),
      },
      {
        path: 'settings',
        element: <LazyRoute><NotFound /></LazyRoute>,
      },
    ],
  },
  {
    path: '*',
    element: (
      <LazyRoute>
        <NotFound />
      </LazyRoute>
    ),
  },
])

export function AppRouter() {
  return <RouterProvider router={router} />
}
