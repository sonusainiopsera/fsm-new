/**
 * @fileoverview Portal surface — Customer self-service portal.
 * Internet-facing, strictly row-scoped.
 * Routes: /portal → /portal/requests/new, /portal/requests/:id/status
 */

import { lazy, Suspense } from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { LoadingState } from '../../components/index.js'

const NewServiceRequestPage = lazy(() => import('../../features/portal/NewServiceRequestPage.jsx'))
const ServiceRequestStatusPage = lazy(() => import('../../features/portal/ServiceRequestStatusPage.jsx'))

function PortalFallback() {
  return <LoadingState />
}

export default function PortalSurface() {
  return (
    <Routes>
      <Route
        index
        element={<Navigate to="requests/new" replace />}
      />
      <Route
        path="requests/new"
        element={
          <Suspense fallback={<PortalFallback />}>
            <NewServiceRequestPage />
          </Suspense>
        }
      />
      <Route
        path="requests/:id/status"
        element={
          <Suspense fallback={<PortalFallback />}>
            <ServiceRequestStatusPage />
          </Suspense>
        }
      />
      <Route
        path="*"
        element={<Navigate to="requests/new" replace />}
      />
    </Routes>
  )
}
