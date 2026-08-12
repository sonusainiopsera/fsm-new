import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AppearanceProvider } from './providers/AppearanceProvider.jsx'
import { QueryProvider } from './providers/QueryProvider.jsx'
import { NotificationPreferencesPage } from './features/settings/notifications/NotificationPreferencesPage.jsx'
import './design-system/tokens.css'

// In a real app, the userId would come from an auth context.
// For this prototype, we use a fixed demo user.
const DEMO_USER_ID = 'user-demo-001'

export function App() {
  return (
    <AppearanceProvider>
      <QueryProvider>
        <BrowserRouter>
          <Routes>
            <Route
              path="/settings/notifications"
              element={<NotificationPreferencesPage userId={DEMO_USER_ID} />}
            />
            <Route
              path="*"
              element={<Navigate to="/settings/notifications" replace />}
            />
          </Routes>
        </BrowserRouter>
      </QueryProvider>
    </AppearanceProvider>
  )
}
