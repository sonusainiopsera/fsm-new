/**
 * Dispatcher surface — route group for /dispatch/*.
 *
 * Entry chunk for the dispatch surface.
 * Roles: DISPATCHER, ADMIN, MANAGER.
 *
 * Routes:
 *   /dispatch                                      → WorkOrderBoardPage (default)
 *   /dispatch/:workOrderId/recommendations         → DispatchRecommendationsPage
 */

import React from 'react';
import { Routes, Route } from 'react-router-dom';
import WorkOrderBoardPage from '../../features/workorders/WorkOrderBoardPage.jsx';
import DispatchRecommendationsPage from '../../features/dispatch/DispatchRecommendationsPage.jsx';

export default function DispatchSurface() {
  return (
    <Routes>
      <Route index element={<WorkOrderBoardPage />} />
      <Route path=":workOrderId/recommendations" element={<DispatchRecommendationsPage />} />
    </Routes>
  );
}
