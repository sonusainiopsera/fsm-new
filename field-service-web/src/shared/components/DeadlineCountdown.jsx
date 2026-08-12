/**
 * DeadlineCountdown — shared skew-corrected deadline timer.
 *
 * Design rules (WO-131):
 * - Drives from the shared serverClock tick; no per-instance interval.
 * - Announces ONLY threshold crossings (at-risk, breached) via a polite
 *   aria-live region — never on every tick.
 * - Reconciles immediately on visibilitychange (handled by serverClock).
 * - Encodes state by icon, text, AND colour — never colour alone (WCAG 1.4.1).
 *
 * @module shared/components/DeadlineCountdown
 */

import React, { useState, useEffect, useRef, useCallback, useId } from 'react';

import { serverNow, subscribeToTick } from '../time/serverClock.js';

// ---- Types --------------------------------------------------------------

/** @typedef {'UPCOMING' | 'AT_RISK' | 'BREACHED'} CountdownState */

// ---- Helpers ------------------------------------------------------------

/**
 * Format milliseconds remaining as a compact duration string.
 * e.g. 90061000 → "25h 01m 01s", -3600000 → "+1h 00m 00s" (overrun)
 *
 * @param {number} remainingMs  May be negative (already breached).
 * @param {boolean} breached
 * @returns {string}
 */
function formatRemaining(remainingMs, breached) {
  const absMs  = Math.abs(remainingMs);
  const totalS = Math.floor(absMs / 1000);
  const s      = totalS % 60;
  const totalM = Math.floor(totalS / 60);
  const m      = totalM % 60;
  const h      = Math.floor(totalM / 60);

  const parts = [];
  if (h > 0)  parts.push(`${h}h`);
  parts.push(`${String(m).padStart(2, '0')}m`);
  parts.push(`${String(s).padStart(2, '0')}s`);

  const duration = parts.join(' ');
  return breached ? `+${duration} overdue` : duration;
}

/**
 * Derive the countdown state from remaining time.
 *
 * @param {number} remainingMs
 * @param {number | null} atRiskAtMs  Epoch ms of the at-risk threshold, or null.
 * @returns {CountdownState}
 */
function deriveState(remainingMs, atRiskAtMs) {
  if (remainingMs <= 0) return 'BREACHED';
  const now = serverNow();
  if (atRiskAtMs !== null && now >= atRiskAtMs) return 'AT_RISK';
  return 'UPCOMING';
}

// ---- Component ----------------------------------------------------------

/**
 * @param {{
 *   deadline: string | null,
 *   atRiskAt?: string | null,
 *   label?: string,
 *   className?: string,
 * }} props
 *
 * - `deadline`   ISO 8601 deadline instant (null → renders "—")
 * - `atRiskAt`   ISO 8601 at-risk threshold instant (optional; derived by
 *                the server from atRiskFraction × resolutionMinutes)
 * - `label`      Accessible label for the countdown region (e.g. "Response deadline")
 */
export function DeadlineCountdown({ deadline, atRiskAt = null, label = 'Deadline', className = '' }) {
  const liveId = useId();

  const deadlineMs   = deadline  ? Date.parse(deadline)  : null;
  const atRiskAtMs   = atRiskAt  ? Date.parse(atRiskAt)  : null;

  const computeSnapshot = useCallback(() => {
    if (deadlineMs === null) return { remainingMs: null, state: 'UPCOMING', text: '—' };
    const remaining = deadlineMs - serverNow();
    const state     = deriveState(remaining, atRiskAtMs);
    return { remainingMs: remaining, state, text: formatRemaining(remaining, state === 'BREACHED') };
  }, [deadlineMs, atRiskAtMs]);

  const [snapshot, setSnapshot] = useState(computeSnapshot);

  // Track previous state to announce only threshold crossings.
  const prevStateRef = useRef(snapshot.state);
  // Announcement text — set to non-empty on crossing, cleared after render.
  const [announcement, setAnnouncement] = useState('');

  const tick = useCallback(() => {
    setSnapshot((prev) => {
      const next = computeSnapshot();
      if (next.state !== prev.state) {
        // Threshold crossing — schedule an announcement.
        const msg =
          next.state === 'AT_RISK'  ? `${label} is now at risk.` :
          next.state === 'BREACHED' ? `${label} has been breached.` :
          '';
        if (msg) setAnnouncement(msg);
      }
      return next;
    });
  }, [computeSnapshot, label]);

  // Subscribe to the shared tick driver.
  useEffect(() => {
    const unsub = subscribeToTick(tick);
    return unsub;
  }, [tick]);

  // Clear announcement text after one render cycle so the live region
  // does not repeat on subsequent ticks.
  useEffect(() => {
    if (!announcement) return;
    const id = setTimeout(() => setAnnouncement(''), 500);
    return () => clearTimeout(id);
  }, [announcement]);

  if (deadlineMs === null) {
    return <span className={className}>—</span>;
  }

  const { state, text } = snapshot;

  const icon =
    state === 'BREACHED' ? '🔴' :
    state === 'AT_RISK'  ? '🟡' :
    '🟢';

  const stateClass =
    state === 'BREACHED' ? 'deadline-breached' :
    state === 'AT_RISK'  ? 'deadline-at-risk'  :
    'deadline-upcoming';

  return (
    <>
      <span
        className={[stateClass, className].filter(Boolean).join(' ')}
        aria-label={`${label}: ${text}`}
        title={`${label}: ${new Date(deadlineMs).toLocaleString()}`}
      >
        <span aria-hidden="true">{icon} </span>
        {text}
      </span>
      {/* Polite live region — only populated on threshold crossings. */}
      <span
        id={liveId}
        role="status"
        aria-live="polite"
        aria-atomic="true"
        style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0,0,0,0)' }}
      >
        {announcement}
      </span>
    </>
  );
}
