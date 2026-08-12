import React from 'react';
import { usePositionSharing } from '../PositionSharingContext.js';
import styles from './SharingIndicator.module.css';

/**
 * Displays a location-sharing active indicator in the app bar.
 * Hidden when sharing is inactive so no reporting occurs silently.
 */
export function SharingIndicator() {
  const { sharing } = usePositionSharing();

  if (!sharing) return null;

  return (
    <span
      className={styles.indicator}
      role="status"
      aria-label="Location sharing active"
      title="Sharing your location for job routing"
    >
      <svg
        className={styles.icon}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
        width="16"
        height="16"
      >
        <circle cx="12" cy="12" r="10" />
        <circle cx="12" cy="12" r="3" />
      </svg>
      <span className={styles.label}>Sharing</span>
    </span>
  );
}
