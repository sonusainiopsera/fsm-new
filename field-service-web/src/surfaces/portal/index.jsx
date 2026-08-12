/**
 * @fileoverview Portal surface — Customer self-service portal.
 * Internet-facing, strictly row-scoped.
 * Routes:
 *   /portal                              → redirect to requests/new
 *   /portal/requests/new                 → NewServiceRequestPage
 *   /portal/requests/:id/status          → ServiceRequestStatusPage
 *   /portal/history                      → ServiceHistoryPage (WO-175)
 *   /portal/surveys/:workOrderId         → SurveyPage (WO-175)
 */

import { lazy, Suspense } from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { LoadingState } from '../../components/index.js'

const NewServiceRequestPage = lazy(() => import('../../features/portal/NewServiceRequestPage.jsx'))
const ServiceRequestStatusPage = lazy(() => import('../../features/portal/ServiceRequestStatusPage.jsx'))
const ServiceHistoryPage = lazy(() => import('../../routes/portal/ServiceHistoryPage.jsx'))
const SurveyPage = lazy(() => import('../../routes/portal/SurveyPage.jsx'))

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
        path="history"
        element={
          <Suspense fallback={<PortalFallback />}>
            <ServiceHistoryPage />
          </Suspense>
        }
      />
      <Route
        path="surveys/:workOrderId"
        element={
          <Suspense fallback={<PortalFallback />}>
            <SurveyPage />
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
