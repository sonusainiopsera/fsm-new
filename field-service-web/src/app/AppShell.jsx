/**
 * @fileoverview AppShell — the structural frame for all four persona surfaces.
 *
 * Landmarks: <header> (banner), <nav> (navigation), <main>.
 * Keyboard traversal order: skip link → top bar → sidebar → main content.
 * CSS Grid layout uses token spacing and a 1440px content cap.
 *
 * Uses only WO-087 primitives and WO-086 token values. Zero bespoke styling.
 */
import { Outlet, useRouteError } from 'react-router-dom'
import { useAuth } from './AuthContext.js'
import { filterNavForRoles } from './navigation.js'
import { Sidebar } from './Sidebar/Sidebar.jsx'
import { TopBar } from './TopBar/TopBar.jsx'
import { ErrorBoundary } from './ErrorBoundary.jsx'
import styles from './AppShell.module.css'

function RouteErrorBoundary() {
  const error = useRouteError()
  return <ErrorBoundary error={error} />
}

export function AppShell() {
  const { roles } = useAuth()
  const navItems = filterNavForRoles(roles)

  return (
    <>
      <a href="#main-content" className={styles.skipLink}>
        Skip to main content
      </a>

      <div className={styles.shell}>
        <TopBar />

        <Sidebar navItems={navItems} aria-label="Primary navigation" />

        <main id="main-content" className={styles.main} tabIndex={-1}>
          <ErrorBoundary>
            <Outlet />
          </ErrorBoundary>
        </main>
      </div>
    </>
  )
}

export { RouteErrorBoundary }
