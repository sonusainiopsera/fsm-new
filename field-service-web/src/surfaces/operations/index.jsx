import React from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import DashboardPage from '../../features/dashboard/DashboardPage.jsx';
import DrillDownPage from '../../features/dashboard/DrillDownPage.jsx';

/**
 * Operations surface — route group for /operations/*.
 *
 * Lazily loaded entry chunk. All chart and KPI widget code lives in this
 * chunk only so the technician PWA bundle is unaffected.
 *
 * Roles: MANAGER, ADMIN.
 */
export default function OperationsSurface() {
  return (
    <Routes>
      <Route index element={<DashboardPage />} />
      <Route path="dashboard" element={<DashboardPage />} />
      <Route path="drill-down" element={<DrillDownPage />} />
      <Route path="*" element={<Navigate to="" replace />} />
    </Routes>
  );
}
