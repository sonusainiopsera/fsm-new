/**
 * @fileoverview TechnicianShell — installable PWA shell for the technician role.
 *
 * Provides:
 * - 360 px one-handed mobile layout with 44 px minimum touch targets (AC-6)
 * - MobileAppBar with appearance toggle (AC-7)
 * - NotConnectedBanner driving the offline state indicator (AC-4)
 * - BottomActionBar with four primary navigation destinations (AC-6)
 * - Suspense boundary around each lazy-loaded screen (AC-1)
 * - ErrorBoundary wrapping the full route tree with traceId recovery (AC-8)
 * - Connectivity guard blocking mutations when offline (AC-5)
 * - SW update prompt using skip-waiting (AC-9)
 *
 * Layout is strictly token-driven — no bespoke CSS values.
 * Never renders raw server error text; always surfaces traceId if available.
 */
import { Suspense, useCallback, useEffect, useState } from 'react'
import { Routes, Route, NavLink } from 'react-router-dom'
import { useConnectivity } from '../../shared/hooks/useConnectivity.js'
import { useAppearance } from '../../appearance/AppearanceProvider.jsx'
import { ErrorBoundary } from '../ErrorBoundary.jsx'
import { NotConnectedBanner } from './NotConnectedBanner.jsx'
import { registerFieldServiceWorker } from '../../serviceWorker/register.js'
import {
  JobsListScreen,
  JobDetailScreen,
  MapScreen,
  PartsScreen,
  ProfileScreen,
  LogWorkScreen,
  TECHNICIAN_NAV,
} from './routes.js'
import { PositionSharingProvider, usePositionSharing } from './PositionSharingContext.jsx'
import { SharingIndicator } from './components/SharingIndicator.jsx'
import styles from './TechnicianShell.module.css'

// ── Suspense skeleton ────────────────────────────────────────────────────────

function ScreenSkeleton() {
  return (
    <div className={styles.shellSkeleton} aria-busy="true" aria-label="Loading">
      {[1, 2, 3].map(n => (
        <div key={n} className={styles.skeletonCard} />
      ))}
    </div>
  )
}

// ── SW update prompt ─────────────────────────────────────────────────────────

function SwUpdateBanner({ onApply }) {
  return (
    <div className={styles.updateBanner} role="status" aria-live="polite">
      <span className={styles.updateMessage}>
        A new version is available — apply it when you&apos;re ready.
      </span>
      <button
        type="button"
        className={styles.updateButton}
        onClick={onApply}
        aria-label="Apply update"
      >
        Update
      </button>
    </div>
  )
}

// ── Bottom navigation item ────────────────────────────────────────────────────

function NavItem({ to, label, icon }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        [styles.navItem, isActive ? styles.active : ''].filter(Boolean).join(' ')
      }
      aria-label={label}
    >
      <span className={styles.navIcon} aria-hidden="true">{icon}</span>
      <span className={styles.navLabel}>{label}</span>
    </NavLink>
  )
}

// ── Main shell ────────────────────────────────────────────────────────────────

function TechnicianShellInner() {
  const { isConnected, isDegraded, lastConnectedAt, retryNow } = useConnectivity()
  const { preference, setPreference } = useAppearance()
  const { isSharing } = usePositionSharing()
  const [swUpdateReady, setSwUpdateReady] = useState(false)
  const [waitingWorker, setWaitingWorker] = useState(null)

  // Register service worker and wire the update prompt
  useEffect(() => {
    registerFieldServiceWorker({
      onUpdateReady: (worker) => {
        setWaitingWorker(worker)
        setSwUpdateReady(true)
      },
    })
  }, [])

  // AC-9: skip-waiting on user confirmation — reload on next navigation
  const handleApplyUpdate = useCallback(() => {
    if (waitingWorker) {
      waitingWorker.postMessage({ type: 'SKIP_WAITING' })
    }
    setSwUpdateReady(false)
    // Reload to activate the new SW on next navigation
    window.location.reload()
  }, [waitingWorker])

  // Appearance toggle cycles LIGHT → DARK → SYSTEM
  const handleAppearanceToggle = useCallback(() => {
    const next = preference === 'LIGHT'
      ? 'DARK'
      : preference === 'DARK'
        ? 'SYSTEM'
        : 'LIGHT'
    setPreference(next)
  }, [preference, setPreference])

  const appearanceIcon = preference === 'DARK' ? '🌙' : preference === 'SYSTEM' ? '⚙' : '☀'
  const appearanceLabel = `Appearance: ${preference ?? 'LIGHT'}. Click to toggle.`

  return (
    <div className={styles.shell} data-testid="technician-shell">
      {/* App bar */}
      <header className={styles.appBar}>
        <span className={styles.appBarTitle}>Field Service</span>
        <div className={styles.appBarActions}>
          <SharingIndicator isSharing={isSharing} />
          <button
            type="button"
            className={styles.appearanceToggle}
            onClick={handleAppearanceToggle}
            aria-label={appearanceLabel}
            data-testid="appearance-toggle"
          >
            {appearanceIcon}
          </button>
        </div>
      </header>

      {/* SW update prompt (AC-9) */}
      {swUpdateReady && (
        <SwUpdateBanner onApply={handleApplyUpdate} />
      )}

      {/* Offline banner (AC-4) */}
      <NotConnectedBanner
        isVisible={!isConnected}
        lastConnectedAt={lastConnectedAt}
        onRetry={retryNow}
      />

      {/* Route content */}
      <main id="technician-main" className={styles.main} tabIndex={-1}>
        <ErrorBoundary>
          <Suspense fallback={<ScreenSkeleton />}>
            <Routes>
              <Route
                index
                element={<JobsListScreen isConnected={isConnected} />}
              />
              <Route
                path="jobs"
                element={<JobsListScreen isConnected={isConnected} />}
              />
              <Route
                path="jobs/:workOrderId"
                element={<JobDetailScreen />}
              />
              <Route
                path="jobs/:workOrderId/log"
                element={<LogWorkScreen />}
              />
              <Route path="map" element={<MapScreen />} />
              <Route path="parts" element={<PartsScreen />} />
              <Route path="me" element={<ProfileScreen />} />
            </Routes>
          </Suspense>
        </ErrorBoundary>
      </main>

      {/* Bottom navigation (AC-6) */}
      <nav
        className={styles.bottomNav}
        aria-label="Primary navigation"
        data-testid="bottom-nav"
      >
        {TECHNICIAN_NAV.map(({ key, path, label, icon }) => (
          <NavItem key={key} to={path} label={label} icon={icon} />
        ))}
      </nav>
    </div>
  )
}

export default function TechnicianShell() {
  return (
    <PositionSharingProvider>
      <TechnicianShellInner />
    </PositionSharingProvider>
  )
}
