/**
 * @fileoverview Settings surface — user-level preferences and configuration.
 *
 * Routes:
 *   /settings/notifications  — notification channel preferences (WO-197)
 */
import { lazy, Suspense } from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { LoadingState } from '../../components/index.js'

const NotificationPreferencesPage = lazy(() =>
  import('../../features/settings/notifications/NotificationPreferencesPage.jsx')
)

function S({ children }) {
  return <Suspense fallback={<LoadingState />}>{children}</Suspense>
}

/**
 * @param {{ userId?: string }} props — the authenticated user's ID, passed from the app shell
 */
export default function SettingsSurface({ userId }) {
  return (
    <Routes>
      <Route index element={<Navigate to="notifications" replace />} />
      <Route
        path="notifications"
        element={<S><NotificationPreferencesPage userId={userId} /></S>}
      />
      <Route path="*" element={<Navigate to="notifications" replace />} />
    </Routes>
  )
}
