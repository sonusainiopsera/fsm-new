/**
 * Settings surface — route group for /settings/*.
 *
 * Accessible to all authenticated users for their own preferences.
 * ADMIN users can access any user's preferences via the direct userId URL.
 *
 * Routes:
 *   /settings/notifications   → NotificationPreferencesPage (current user)
 */

import React from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuth } from '../../app/AuthContext.js';
import NotificationPreferencesPage from '../../features/settings/notifications/NotificationPreferencesPage.jsx';

/**
 * @param {{ roles?: string[] }} props
 */
export default function SettingsSurface({ roles = [] }) {
  const { token } = useAuth();
  const userId = token?.sub ?? null;

  return (
    <Routes>
      <Route index element={<Navigate to="notifications" replace />} />
      <Route
        path="notifications"
        element={<NotificationPreferencesPage userId={userId} />}
      />
    </Routes>
  );
}
