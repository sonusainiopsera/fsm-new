import React, { Suspense, lazy } from 'react';
import { createBrowserRouter, Navigate, useRouteError } from 'react-router-dom';
import { AppShell } from './AppShell.jsx';
import { LoadingState, PermissionDeniedState, ErrorState } from '../components/index.js';

// Route-level code splitting: each surface is a separate async chunk.
// Technician (field) and dispatcher chunks are separated so technicians on
// mobile connections never download the dispatcher board or charting bundles.
// See vite.config.js manualChunks for build-level enforcement.

const LazyDispatch = lazy(() => import('../surfaces/dispatch/index.jsx'));
const LazyField = lazy(() => import('../surfaces/field/index.jsx'));
const LazyOperations = lazy(() => import('../surfaces/operations/index.jsx'));
const LazyPortal = lazy(() => import('../surfaces/portal/index.jsx'));
const LazyInventory = lazy(() => import('../features/inventory/index.jsx'));
const LazySignIn = lazy(() => import('../surfaces/auth/SignIn.jsx'));

/** Shared Suspense wrapper bound to the LoadingState skeleton primitive. */
function SurfaceSuspense({ children }) {
  return (
    <Suspense fallback={<LoadingState />}>
      {children}
    </Suspense>
  );
}

/**
 * Router-level error element.
 * Maps server 403 responses surfaced by the WO-090 query error mapper to the
 * PermissionDeniedState primitive with no existence disclosure.
 * All other errors render ErrorState. Never renders a stack trace (A10).
 */
function RouteError() {
  const error = useRouteError();
  const is403 = error?.status === 403 || error?.statusCode === 403;

  if (is403) {
    return <PermissionDeniedState />;
  }

  const traceId = error?.traceId ?? error?.data?.traceId ?? null;
  return (
    <ErrorState
      description={traceId ? `Reference: ${traceId}` : 'Please try refreshing the page.'}
    />
  );
}

function NotFound() {
  return (
    <ErrorState
      title="Page not found"
      description="The page you are looking for does not exist."
    />
  );
}

/**
 * The application router built with React Router 6 createBrowserRouter.
 *
 * SECURITY NOTE (A01, BR-19):
 * This router contains NO client-side authorisation guards. The shell filters
 * navigation items for usability only. Accessing a route without the required
 * server-side role produces a 403 response from the API, which the WO-090
 * query error mapper surfaces as PermissionDeniedState. The client never grants
 * or denies access based on token contents alone.
 */
export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppShell />,
    errorElement: <RouteError />,
    children: [
      {
        index: true,
        element: <Navigate to="/dispatch" replace />,
      },
      {
        path: 'dispatch/*',
        element: (
          <SurfaceSuspense>
            <LazyDispatch />
          </SurfaceSuspense>
        ),
      },
      {
        path: 'field/*',
        element: (
          <SurfaceSuspense>
            <LazyField />
          </SurfaceSuspense>
        ),
      },
      {
        path: 'operations/*',
        element: (
          <SurfaceSuspense>
            <LazyOperations />
          </SurfaceSuspense>
        ),
      },
      {
        path: 'portal/*',
        element: (
          <SurfaceSuspense>
            <LazyPortal />
          </SurfaceSuspense>
        ),
      },
      {
        path: 'inventory/*',
        element: (
          <SurfaceSuspense>
            <LazyInventory />
          </SurfaceSuspense>
        ),
      },
    ],
  },
  {
    path: '/sign-in',
    element: (
      <SurfaceSuspense>
        <LazySignIn />
      </SurfaceSuspense>
    ),
  },
  {
    // Forgot-password flow is deferred — customer identity model not yet ratified (WO-116).
    path: '/forgot-password',
    element: (
      <ErrorState
        title="Password reset unavailable"
        description="Self-service password reset is coming soon. Please contact your administrator."
      />
    ),
  },
  {
    path: '*',
    element: <NotFound />,
  },
]);
