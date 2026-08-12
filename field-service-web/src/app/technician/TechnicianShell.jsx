import React, { useEffect, useState, useCallback } from 'react';
import { Outlet, NavLink, useNavigate } from 'react-router-dom';
import { ErrorBoundary } from '../ErrorBoundary.jsx';
import { useAppearance } from '../../appearance/AppearanceContext.js';
import { useConnectivity } from '../../shared/hooks/useConnectivity.js';
import { writeUserAppearance } from '../../shared/theme/appearance.js';
import { NotConnectedBanner } from './NotConnectedBanner.jsx';
import { ConnectivityContext } from './ConnectivityContext.js';
import styles from './TechnicianShell.module.css';

/**
 * Technician PWA shell.
 *
 * Mobile-first layout for the /technician/* route group:
 *  - 360 px one-handed layout, no horizontal scroll at 360 px
 *  - 44 × 44 CSS-pixel minimum touch targets on all interactive controls
 *  - MobileAppBar with appearance toggle that persists per user account
 *  - Persistent NotConnectedBanner when offline (disappears within 2 s of
 *    connectivity returning, bounded by the useConnectivity debounce)
 *  - Mutation guard via ConnectivityContext — child screens call assertOnline()
 *    before any write, which throws NetworkOfflineError rather than queueing
 *  - Error boundary with traceId-aware recovery UI (never shows raw errors)
 *  - Service worker registration on mount + skip-waiting update prompt
 *
 * The shell does NOT manage auth — that is handled by AppShell's boot guard.
 * If the user is not authenticated they are redirected to /sign-in before
 * reaching this shell.
 */
export function TechnicianShell() {
  const connectivity = useConnectivity();
  const { appearance, toggleAppearance } = useAppearance();
  const navigate = useNavigate();
  const [updateApply, setUpdateApply] = useState(null);

  // Register service worker on first mount
  useEffect(() => {
    import('../../pwa/serviceWorkerRegistration.js')
      .then(({ registerTechnicianServiceWorker }) =>
        registerTechnicianServiceWorker({
          onUpdateAvailable: (applyFn) => setUpdateApply(() => applyFn),
        })
      )
      .catch(() => { /* SW registration failure is non-fatal */ });
  }, []);

  const handleRetry = useCallback(() => {
    // Trigger re-render; connectivity hook re-runs heartbeat on its own interval
    navigate(0);
  }, [navigate]);

  const handleApplyUpdate = useCallback(() => {
    if (updateApply) {
      updateApply();
      setUpdateApply(null);
    }
  }, [updateApply]);

  return (
    <ConnectivityContext.Provider value={connectivity}>
      <div className={styles.shell}>
        {/* Mobile app bar */}
        <header className={styles.appBar} role="banner">
          <span className={styles.appBarTitle}>My Jobs</span>
          <button
            type="button"
            className={styles.appearanceToggle}
            onClick={toggleAppearance}
            aria-label={`Switch to ${appearance === 'light' ? 'dark' : 'light'} appearance`}
            aria-pressed={appearance === 'dark'}
          >
            {appearance === 'light' ? 'Dark' : 'Light'}
          </button>
        </header>

        {/* SW update prompt — non-disruptive, appears above banner */}
        {updateApply && (
          <div className={styles.updateBar} role="alert" aria-live="polite">
            <span className={styles.updateMessage}>App update available</span>
            <button
              type="button"
              className={styles.updateButton}
              onClick={handleApplyUpdate}
            >
              Update now
            </button>
          </div>
        )}

        {/* Not-connected banner — shown while offline */}
        {connectivity.isOffline && (
          <NotConnectedBanner
            cachedAt={connectivity.cachedAt}
            onRetry={handleRetry}
          />
        )}

        {/* Route outlet wrapped in error boundary */}
        <main
          id="main-content"
          className={styles.main}
          tabIndex={-1}
        >
          <ErrorBoundary key={connectivity.isOffline ? 'offline' : 'online'}>
            <Outlet />
          </ErrorBoundary>
        </main>

        {/* Bottom navigation bar */}
        <nav className={styles.bottomBar} aria-label="Main navigation">
          <NavLink
            to="/technician"
            end
            className={({ isActive }) =>
              `${styles.navItem} ${isActive ? styles.navItemActive : ''}`
            }
            aria-label="Jobs"
          >
            <svg
              className={styles.navIcon}
              aria-hidden="true"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <rect x="2" y="7" width="20" height="14" rx="2" ry="2" />
              <path d="M16 7V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v2" />
            </svg>
            <span className={styles.navLabel}>Jobs</span>
          </NavLink>
        </nav>
      </div>
    </ConnectivityContext.Provider>
  );
}
