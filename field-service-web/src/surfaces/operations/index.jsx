/**
 * @fileoverview Operations surface — Operations Manager dashboard.
 * Charting bundle (Recharts) is isolated to this surface chunk so the
 * technician entry payload never downloads the charting library.
 */
import { Routes, Route } from 'react-router-dom'
import { EmptyState } from '../../components/index.js'

export default function OperationsSurface() {
  return (
    <Routes>
      <Route index element={<EmptyState message="Operations Dashboard — coming soon." />} />
      <Route path="audit" element={<EmptyState message="Audit Log — coming soon." />} />
      <Route path="*" element={<EmptyState message="Operations surface — coming soon." />} />
    </Routes>
  )
}
