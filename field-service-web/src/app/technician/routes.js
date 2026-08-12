import React, { lazy, Suspense } from 'react';
import { TechnicianShell } from './TechnicianShell.jsx';
import { LoadingState } from '../../components/index.js';

// Route-level code splitting: each screen is its own lazy chunk.
const LazyDayList    = lazy(() => import('./DayListScreen.jsx'));
const LazyJobDetail  = lazy(() => import('./JobDetailView.jsx').then(m => ({ default: m.JobDetailView })));
const LazyLogWork    = lazy(() => import('./LogWorkView.jsx').then(m => ({ default: m.LogWorkView })));

function ShellSuspense({ children }) {
  return (
    <Suspense fallback={<LoadingState />}>
      {children}
    </Suspense>
  );
}

/**
 * Technician route group.
 *
 * Mounted at /technician/* by the root router.
 * The TechnicianShell is the mobile-first PWA shell that wraps all
 * technician screens with the connectivity banner, error boundary,
 * and bottom navigation.
 *
 * Bundle-size budget: the technician entry chunk (chunk-technician) must
 * not exceed 100 kB gzipped. Enforced by vite.config.js manualChunks.
 */
export const technicianRoutes = {
  path: 'technician',
  element: <TechnicianShell />,
  children: [
    {
      index: true,
      element: (
        <ShellSuspense>
          <LazyDayList />
        </ShellSuspense>
      ),
    },
    {
      path: ':jobId',
      element: (
        <ShellSuspense>
          <LazyJobDetail />
        </ShellSuspense>
      ),
    },
    {
      path: ':jobId/log-work',
      element: (
        <ShellSuspense>
          <LazyLogWork />
        </ShellSuspense>
      ),
    },
  ],
};
