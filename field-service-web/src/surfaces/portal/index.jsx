/**
 * @fileoverview Portal surface — Customer self-service portal.
 * Internet-facing, strictly row-scoped.
 */
import { Routes, Route } from 'react-router-dom'
import { EmptyState } from '../../components/index.js'

export default function PortalSurface() {
  return (
    <Routes>
      <Route index element={<EmptyState message="Customer Portal — coming soon." />} />
      <Route path="requests" element={<EmptyState message="My Requests — coming soon." />} />
      <Route path="*" element={<EmptyState message="Portal surface — coming soon." />} />
    </Routes>
  )
}
