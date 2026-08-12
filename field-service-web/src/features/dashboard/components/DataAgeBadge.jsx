import React from 'react';
import styles from './DataAgeBadge.module.css';

/**
 * Formats an ISO timestamp as a human-readable relative age (e.g. "5m ago").
 * Returns null if timestamp is absent or unparseable.
 *
 * @param {string | null | undefined} iso
 * @param {number} nowMs
 * @returns {string | null}
 */
function formatAge(iso, nowMs) {
  if (!iso) return null;
  const ms = nowMs - new Date(iso).getTime();
  if (isNaN(ms) || ms < 0) return null;
  const secs = Math.floor(ms / 1000);
  if (secs < 60) return `${secs}s ago`;
  const mins = Math.floor(secs / 60);
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  return `${days}d ago`;
}

/**
 * Small badge showing when widget data was last fetched.
 * Renders nothing when dataAge is absent.
 *
 * @param {{
 *   dataAge: string | null | undefined,
 *   degraded?: boolean,
 * }} props
 */
export function DataAgeBadge({ dataAge, degraded = false }) {
  const [now, setNow] = React.useState(Date.now);

  // Refresh the relative time every 30 seconds
  React.useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(id);
  }, []);

  const label = formatAge(dataAge, now);
  if (!label) return null;

  const cls = [styles.badge, degraded ? styles.degraded : ''].filter(Boolean).join(' ');
  const absTime = dataAge ? new Date(dataAge).toLocaleString() : '';

  return (
    <time
      className={cls}
      dateTime={dataAge ?? undefined}
      title={absTime}
      aria-label={`Data as of ${absTime}`}
    >
      {degraded && <span className={styles.icon} aria-hidden="true">⚠ </span>}
      {label}
    </time>
  );
}
