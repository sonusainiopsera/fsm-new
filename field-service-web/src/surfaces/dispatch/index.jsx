/**
 * @fileoverview Dispatch surface — Dispatcher desktop console.
 * Placeholder until WO-dispatch implements the board and board sub-routes.
 */
import { Routes, Route } from 'react-router-dom'
import { EmptyState } from '../../components/index.js'
import StockPositionsPage from '../../features/inventory/StockPositionsPage.jsx'
import LowStockPage from '../../features/inventory/LowStockPage.jsx'

function DispatchPlaceholder() {
  return <EmptyState message="Dispatch Board — coming soon." />
}

export default function DispatchSurface() {
  return (
    <Routes>
      <Route index element={<DispatchPlaceholder />} />
      <Route path="work-orders" element={<EmptyState message="Work Orders — coming soon." />} />
      <Route path="technicians" element={<EmptyState message="Technicians — coming soon." />} />
      <Route path="inventory" element={<StockPositionsPage />} />
      <Route path="inventory/low-stock" element={<LowStockPage />} />
      <Route path="*" element={<DispatchPlaceholder />} />
    </Routes>
  )
}
