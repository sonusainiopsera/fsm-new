/**
 * @fileoverview TopBar — banner landmark with appearance switch, account menu,
 * network-status indicator, and live stream health indicator.
 *
 * Uses only token values (WO-086) and WO-087 primitives. Zero bespoke literals.
 */
import { useAppearance } from '../../appearance/AppearanceProvider.jsx'
import { useAuth } from '../AuthContext.js'
import { useNetworkStatus } from '../useNetworkStatus.js'
import { LiveStreamIndicator } from './LiveStreamIndicator.jsx'
import styles from './TopBar.module.css'

/**
 * @param {{ streamStatus?: 'live' | 'reconnecting' | 'stale', onStreamRefresh?: () => void }} props
 */
export function TopBar({ streamStatus, onStreamRefresh }) {
  const { preference, setPreference } = useAppearance()
  const { roles } = useAuth()
  const { isOnline } = useNetworkStatus()

  const isDark = preference === 'DARK'
  const nextPreference = isDark ? 'LIGHT' : 'DARK'

  const isDispatchRole = roles.includes('DISPATCHER') || roles.includes('MANAGER')

  const currentSurface = roles.includes('TECHNICIAN')
    ? 'Field'
    : roles.includes('DISPATCHER')
    ? 'Dispatch'
    : roles.includes('MANAGER')
    ? 'Operations'
    : roles.includes('CUSTOMER')
    ? 'Portal'
    : 'Field Service'

  return (
    <header role="banner" className={styles.topbar}>
      <div className={styles.left}>
        <span className={styles.brand}>{currentSurface}</span>
      </div>

      <div className={styles.right}>
        {!isOnline && (
          <div
            role="status"
            aria-live="polite"
            aria-label="No network connection — offline"
            className={styles.offlineBadge}
          >
            <span aria-hidden="true">⚠</span>
            Offline
          </div>
        )}

        {/* Live stream indicator — shown for dispatcher and manager roles only */}
        {isDispatchRole && streamStatus && (
          <LiveStreamIndicator
            status={streamStatus}
            onRefresh={onStreamRefresh}
          />
        )}

        <button
          type="button"
          aria-label={`Switch to ${nextPreference.toLowerCase()} appearance`}
          className={styles.iconBtn}
          onClick={() => setPreference(nextPreference)}
        >
          <span aria-hidden="true">{isDark ? '☀' : '☾'}</span>
        </button>

        <button
          type="button"
          aria-label="Account menu"
          aria-haspopup="menu"
          className={styles.iconBtn}
        >
          <span aria-hidden="true">●</span>
        </button>
      </div>
    </header>
  )
}
