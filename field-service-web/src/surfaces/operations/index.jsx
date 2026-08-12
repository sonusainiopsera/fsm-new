/**
 * @fileoverview Operations surface — Operations Manager dashboard.
 * The dashboard bundle is isolated to this surface chunk via React.lazy so
 * the technician PWA entry payload never downloads it.
 */
import { lazy, Suspense } from 'react'
import { Routes, Route } from 'react-router-dom'
import { EmptyState, LoadingState } from '../../components/index.js'

const DashboardPage = lazy(() => import('../../features/dashboard/DashboardPage.jsx'))

export default function OperationsSurface() {
  return (
    <Routes>
      <Route
        index
        element={
          <Suspense fallback={<LoadingState />}>
            <DashboardPage />
          </Suspense>
        }
      />
      <Route path="audit" element={<EmptyState message="Audit Log — coming soon." />} />
      <Route path="*" element={<EmptyState message="Operations surface — coming soon." />} />
    </Routes>
  )
}
