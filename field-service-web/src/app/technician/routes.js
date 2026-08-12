/**
 * @fileoverview Technician PWA route group — lazy-loaded screen components.
 *
 * Each screen is a separate dynamic import so only the shell chunk loads on
 * first paint. The bundle-size budget for the technician entry chunk is
 * enforced in vite.config.js.
 *
 * Route paths are relative to the parent `/technician` route:
 *   /technician          → JobsListScreen (index)
 *   /technician/jobs     → JobsListScreen
 *   /technician/jobs/:id → JobDetailScreen (lazy)
 *   /technician/map      → MapScreen (lazy)
 *   /technician/parts    → PartsScreen (lazy)
 *   /technician/me       → ProfileScreen (lazy)
 */
import { lazy } from 'react'

export const JobsListScreen = lazy(
  () => import('../../surfaces/field/screens/JobsListScreen.jsx')
)
export const JobDetailScreen = lazy(
  () => import('../../surfaces/field/screens/JobDetailScreen.jsx')
)
export const MapScreen = lazy(
  () => import('../../surfaces/field/screens/MapScreen.jsx')
)
export const PartsScreen = lazy(
  () => import('../../surfaces/field/screens/PartsScreen.jsx')
)
export const ProfileScreen = lazy(
  () => import('../../surfaces/field/screens/ProfileScreen.jsx')
)
export const LogWorkScreen = lazy(
  () => import('../../surfaces/field/screens/LogWorkScreen.jsx')
)

export const TECHNICIAN_NAV = [
  { key: 'jobs',    path: '/technician/jobs',   label: 'Jobs',   icon: '📋' },
  { key: 'map',     path: '/technician/map',    label: 'Map',    icon: '🗺' },
  { key: 'parts',   path: '/technician/parts',  label: 'Parts',  icon: '🔧' },
  { key: 'me',      path: '/technician/me',     label: 'Me',     icon: '👤' },
]
