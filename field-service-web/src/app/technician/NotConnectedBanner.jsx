/**
 * @fileoverview NotConnectedBanner — persistent offline indicator for the
 * technician PWA shell.
 *
 * - Renders only when isVisible is true (i.e. isConnected === false).
 * - Shows the age of cached data via formatCachedAge.
 * - Warns explicitly when cache is older than the shift TTL (12 hours).
 * - Offers a retry action that triggers the connectivity check immediately.
 * - Disappears as soon as connectivity is restored (controlled externally).
 * - Never hides or downplays the not-connected state — AC-4 requires honesty.
 */
import styles from './NotConnectedBanner.module.css'
import { formatCachedAge, isCacheExpired } from '../../shared/time/cachedDataAge.js'

/**
 * @param {{
 *   isVisible: boolean,
 *   lastConnectedAt: number | null,
 *   onRetry: () => void,
 * }} props
 */
export function NotConnectedBanner({ isVisible, lastConnectedAt, onRetry }) {
  if (!isVisible) return null

  const ageMs = lastConnectedAt !== null ? Date.now() - lastConnectedAt : null
  const ageText = ageMs !== null ? formatCachedAge(ageMs) : null
  const expired = ageMs !== null && isCacheExpired(ageMs)

  return (
    <div
      role="status"
      aria-live="polite"
      aria-label="Device not connected"
      className={styles.banner}
      data-testid="not-connected-banner"
    >
      <span className={styles.icon} aria-hidden="true">⚠</span>
      <div className={styles.message}>
        <span className={styles.title}>Not connected</span>
        {ageText !== null && !expired && (
          <span className={styles.age}>Cached data from {ageText}</span>
        )}
        {expired && (
          <span className={styles.expiredWarning}>
            Cached data is older than 12 hours — data may be outdated
          </span>
        )}
      </div>
      <button
        type="button"
        className={styles.retry}
        onClick={onRetry}
        aria-label="Retry connection"
      >
        Retry
      </button>
    </div>
  )
}
