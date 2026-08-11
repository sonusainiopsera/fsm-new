import React, { Suspense, useCallback } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Sidebar } from './Sidebar/Sidebar.jsx';
import { TopBar } from './TopBar/TopBar.jsx';
import { ErrorBoundary } from './ErrorBoundary.jsx';
import { LoadingState } from '../components/index.js';
import { useSidebarCollapse } from './Sidebar/useSidebarCollapse.js';

import styles from './AppShell.module.css';

/**
 * Application shell layout — the structural frame for all four persona surfaces.
 *
 * Landmarks: <header> (banner via TopBar), <nav> (navigation via Sidebar),
 * <main> (route outlet). Skip-to-content link lives in the TopBar.
 *
 * Layout: CSS Grid with a 240px sidebar (64px collapsed icon rail), a 56px
 * top bar, and a scrollable main area capped at 1440px for wide viewports.
 *
 * The route Outlet is wrapped in an ErrorBoundary so unexpected render errors
 * surface the ErrorState primitive with traceId and never a stack trace (A10).
 *
 * Route-level code splitting is handled upstream in router.jsx — each surface
 * renders through React.lazy + Suspense before reaching this shell.
 */
export function AppShell() {
  const location = useLocation();
  const navigate = useNavigate();
  const { collapsed, toggle, isDrawerMode } = useSidebarCollapse();

  const handleNavigation = useCallback((path) => {
    navigate(path);
  }, [navigate]);

  return (
    <div className={styles.shell} data-drawer-mode={isDrawerMode ? 'true' : undefined}>
      <TopBar
        onMenuToggle={toggle}
        isDrawerMode={isDrawerMode}
      />

      <Sidebar
        collapsed={collapsed}
        isDrawerMode={isDrawerMode}
        onToggle={toggle}
        onNavigation={handleNavigation}
        currentPath={location.pathname}
      />

      <main
        id="main-content"
        className={styles.main}
        tabIndex={-1}
      >
        <ErrorBoundary key={location.pathname}>
          <Suspense fallback={<div className={styles.loadingWrapper}><LoadingState /></div>}>
            <Outlet />
          </Suspense>
        </ErrorBoundary>
      </main>
    </div>
  );
}
