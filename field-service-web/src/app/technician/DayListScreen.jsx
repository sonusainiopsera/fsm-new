import React from 'react';
import { useConnectivityContext } from './ConnectivityContext.js';
import styles from './DayListScreen.module.css';

/**
 * Technician day-list screen — lazy-loaded entry point for /technician/.
 *
 * Displays the technician's assigned jobs for the current shift.
 * Full job-card implementation is delivered by downstream technician-feature
 * work orders; this screen establishes the route and connectivity-aware shell.
 *
 * Mutation guard: any write action must call `assertOnline()` first.
 * The ConnectivityContext provides this from TechnicianShell.
 */
export default function DayListScreen() {
  const { isOffline, assertOnline } = useConnectivityContext();

  return (
    <div className={styles.screen} aria-label="Assigned jobs">
      {isOffline ? (
        <p className={styles.offlineNotice}>
          Showing cached jobs. Mutations require a connection.
        </p>
      ) : (
        <p className={styles.placeholder}>Your assigned jobs will appear here.</p>
      )}
    </div>
  );
}
