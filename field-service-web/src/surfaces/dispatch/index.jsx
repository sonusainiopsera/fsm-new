/**
 * @fileoverview Dispatch surface — Dispatcher desktop console.
 * Placeholder until WO-dispatch implements the board and board sub-routes.
 */
import { Outlet, Routes, Route } from 'react-router-dom'
import { EmptyState } from '../../components/index.js'

function DispatchPlaceholder() {
  return <EmptyState message="Dispatch Board — coming soon." />
}

export default function DispatchSurface() {
  return (
    <Routes>
      <Route index element={<DispatchPlaceholder />} />
      <Route path="work-orders" element={<EmptyState message="Work Orders — coming soon." />} />
      <Route path="technicians" element={<EmptyState message="Technicians — coming soon." />} />
      <Route path="inventory" element={<EmptyState message="Inventory — coming soon." />} />
      <Route path="*" element={<DispatchPlaceholder />} />
    </Routes>
  )
}
