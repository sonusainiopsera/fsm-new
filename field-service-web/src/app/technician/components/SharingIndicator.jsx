/**
 * @fileoverview SharingIndicator — app bar chip shown while position reporting is active.
 *
 * Renders nothing when isSharing is false so the app bar is uncluttered
 * during ASSIGNED / ON_HOLD / COMPLETED states.
 */
import styles from './SharingIndicator.module.css'

/**
 * @param {{ isSharing: boolean }} props
 */
export function SharingIndicator({ isSharing }) {
  if (!isSharing) return null

  return (
    <div
      className={styles.chip}
      role="status"
      aria-label="Sharing location with dispatch"
      data-testid="sharing-indicator"
    >
      <span className={styles.dot} aria-hidden="true" />
      <span className={styles.label}>Sharing</span>
    </div>
  )
}
