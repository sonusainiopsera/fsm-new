/**
 * SlaCountdownChip — live SLA countdown for the technician job detail screen.
 *
 * Updates every 30 seconds via a shell-level interval timer to avoid per-card
 * timer proliferation. Derives at-risk status from the server flag — never
 * recomputes thresholds client-side.
 *
 * Overrun (deadline already passed) renders a distinct visual state rather
 * than a negative timer.
 *
 * @module app/technician/components/SlaCountdownChip
 */

import React, { useState, useEffect } from 'react';
import styles from './SlaCountdownChip.module.css';

/**
 * Formats the remaining duration from `deadline` to `now`.
 * @param {string | null} deadline ISO timestamp
 * @param {Date} now
 * @returns {{ label: string, overrun: boolean, minutesLeft: number }}
 */
export function formatCountdown(deadline, now) {
  if (!deadline) return { label: 'No deadline', overrun: false, minutesLeft: Infinity };
  const deadlineMs = new Date(deadline).getTime();
  const remaining  = deadlineMs - now.getTime();

  if (remaining <= 0) {
    const overrunMins = Math.ceil(Math.abs(remaining) / 60_000);
    return { label: `${overrunMins}m overrun`, overrun: true, minutesLeft: -overrunMins };
  }

  const totalMins = Math.ceil(remaining / 60_000);
  if (totalMins >= 60) {
    const h = Math.floor(totalMins / 60);
    const m = totalMins % 60;
    return { label: m > 0 ? `${h}h ${m}m` : `${h}h`, overrun: false, minutesLeft: totalMins };
  }
  return { label: `${totalMins}m`, overrun: false, minutesLeft: totalMins };
}

/**
 * @param {{
 *   resolutionDeadline: string | null,
 *   slaAtRisk: boolean,
 * }} props
 */
export function SlaCountdownChip({ resolutionDeadline, slaAtRisk }) {
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 30_000);
    return () => clearInterval(id);
  }, []);

  const { label, overrun } = formatCountdown(resolutionDeadline, now);

  const chipClass = [
    styles.chip,
    overrun    ? styles.overrun  : '',
    slaAtRisk  ? styles.atRisk   : '',
    !overrun && !slaAtRisk ? styles.healthy : '',
  ].filter(Boolean).join(' ');

  return (
    <span
      className={chipClass}
      aria-label={`SLA: ${label}${slaAtRisk ? ', at risk' : ''}${overrun ? ', overrun' : ''}`}
      role="status"
    >
      {label}
    </span>
  );
}
