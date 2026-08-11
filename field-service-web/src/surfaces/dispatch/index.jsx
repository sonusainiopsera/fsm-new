/**
 * @fileoverview Dispatch surface — Dispatcher desktop console.
 */
import { Routes, Route } from 'react-router-dom'
import { EmptyState } from '../../components/index.js'
import StockPositionsPage from '../../features/inventory/StockPositionsPage.jsx'
import LowStockPage from '../../features/inventory/LowStockPage.jsx'
import WorkOrderBoardPage from '../../features/workorders/WorkOrderBoardPage.jsx'

export default function DispatchSurface() {
  return (
    <Routes>
      <Route index element={<WorkOrderBoardPage />} />
      <Route path="work-orders" element={<WorkOrderBoardPage />} />
      <Route path="technicians" element={<EmptyState message="Technicians — coming soon." />} />
      <Route path="inventory" element={<StockPositionsPage />} />
      <Route path="inventory/low-stock" element={<LowStockPage />} />
      <Route path="*" element={<WorkOrderBoardPage />} />
    </Routes>
  )
}
