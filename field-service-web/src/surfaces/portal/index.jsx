import React, { lazy, Suspense } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { LoadingState } from '../../components/index.js';

/**
 * Customer Portal surface — route group for /portal/*.
 *
 * Roles: CUSTOMER.
 * Sub-routes are lazy-loaded within this already-lazy surface chunk so that
 * the NewServiceRequestPage form library and StatusPage polling code don't
 * bloat the initial portal bundle.
 */

const LazyNewServiceRequestPage = lazy(() => import('../../routes/portal/NewServiceRequestPage.jsx'));
const LazyServiceRequestStatusPage = lazy(() => import('../../routes/portal/ServiceRequestStatusPage.jsx'));

function PortalSuspense({ children }) {
  return (
    <Suspense fallback={<LoadingState label="Loading…" />}>
      {children}
    </Suspense>
  );
}

export default function PortalSurface() {
  return (
    <Routes>
      <Route
        path="new"
        element={
          <PortalSuspense>
            <LazyNewServiceRequestPage />
          </PortalSuspense>
        }
      />
      <Route
        path="status/:requestId"
        element={
          <PortalSuspense>
            <LazyServiceRequestStatusPage />
          </PortalSuspense>
        }
      />
      {/* Default redirect: /portal → /portal/new */}
      <Route index element={<Navigate to="new" replace />} />
      <Route path="*" element={<Navigate to="new" replace />} />
    </Routes>
  );
}
