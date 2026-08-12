import React from 'react';
import { formatCachedAge } from '../../shared/hooks/useConnectivity.js';
import styles from './NotConnectedBanner.module.css';

/**
 * Persistent banner shown when the device is not connected.
 *
 * Shows the age of the last cached day-list response and a retry action.
 * The banner disappears automatically when `useConnectivity` transitions to
 * online — the parent shell unmounts it once `isOffline` becomes false.
 *
 * Accessibility: role="status" so screen readers announce the banner without
 * interrupting task flow; aria-live="polite" defers the announcement.
 *
 * @param {{ cachedAt: Date|null, onRetry?: () => void }} props
 */
export function NotConnectedBanner({ cachedAt, onRetry }) {
  const ageLabel = formatCachedAge(cachedAt);

  return (
    <div
      className={styles.banner}
      role="status"
      aria-live="polite"
      aria-label="Not connected"
      data-testid="not-connected-banner"
    >
      <div className={styles.content}>
        <span className={styles.icon} aria-hidden="true" />
        <div className={styles.text}>
          <strong className={styles.heading}>Not connected</strong>
          {ageLabel && (
            <span className={styles.detail}>
              Showing cached data from {ageLabel}
            </span>
          )}
          {!ageLabel && (
            <span className={styles.detail}>
              Cached data may be unavailable
            </span>
          )}
        </div>
      </div>
      <button
        type="button"
        className={styles.retryButton}
        onClick={onRetry}
        aria-label="Retry connection"
      >
        Retry
      </button>
    </div>
  );
}
