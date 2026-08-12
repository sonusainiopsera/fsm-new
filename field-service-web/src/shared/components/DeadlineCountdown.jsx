/**
 * @fileoverview DeadlineCountdown — shared skew-corrected deadline countdown.
 *
 * Renders remaining time for response or resolution deadlines. Derives time
 * from serverNow() rather than Date.now() so a device with a skewed local
 * clock always shows the correct interval.
 *
 * State transitions:
 *   normal  → at_risk  when serverNow() reaches atRiskAt
 *   at_risk  → breached when serverNow() passes deadlineAt
 *
 * Each state change is announced once via an aria-live polite region.
 * Per-second updates are NOT announced (they would be too noisy for AT users).
 *
 * One shared interval driver from serverClock.js is used rather than one
 * setInterval per instance, keeping INP inside budget.
 *
 * @module shared/components/DeadlineCountdown
 */
import { useEffect, useRef, useState } from 'react'
import { serverNow, subscribe } from '../time/serverClock.js'

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * @param {number} absMs  Absolute milliseconds
 * @returns {string}      e.g. "3h 12m" or "7m 44s"
 */
function formatDuration(absMs) {
  const totalSecs = Math.floor(absMs / 1000)
  const hrs = Math.floor(totalSecs / 3600)
  const mins = Math.floor((totalSecs % 3600) / 60)
  const secs = totalSecs % 60
  if (hrs > 0) return `${hrs}h ${mins}m`
  if (mins > 0) return `${mins}m ${secs}s`
  return `${secs}s`
}

/** @typedef {'normal' | 'at_risk' | 'breached'} CountdownState */

/**
 * Derives the current countdown state given server-estimated now.
 *
 * @param {number} nowMs
 * @param {number} deadlineMs
 * @param {number | null} atRiskMs
 * @returns {CountdownState}
 */
function deriveState(nowMs, deadlineMs, atRiskMs) {
  if (nowMs >= deadlineMs) return 'breached'
  if (atRiskMs != null && nowMs >= atRiskMs) return 'at_risk'
  return 'normal'
}

// ── Component ─────────────────────────────────────────────────────────────────

/**
 * @param {{
 *   deadlineAt: string | null,
 *   atRiskAt?: string | null,
 *   label?: string
 * }} props
 *
 * - deadlineAt: ISO-8601 timestamp for the hard deadline
 * - atRiskAt:   ISO-8601 timestamp when the "at-risk" emphasis begins
 * - label:      Used in aria-label and announcements (e.g. "Response", "Resolution")
 */
export function DeadlineCountdown({ deadlineAt, atRiskAt = null, label = 'Deadline' }) {
  const deadlineMs = deadlineAt ? new Date(deadlineAt).getTime() : null
  const atRiskMs = atRiskAt ? new Date(atRiskAt).getTime() : null

  /** Remaining milliseconds — negative when breached. */
  const [remainingMs, setRemainingMs] = useState(() =>
    deadlineMs != null ? deadlineMs - serverNow() : null
  )

  const [countdownState, setCountdownState] = useState(
    /** @type {CountdownState} */ (
      deadlineMs != null
        ? deriveState(serverNow(), deadlineMs, atRiskMs)
        : 'normal'
    )
  )

  // Track previous state to announce only transitions, not every tick
  const prevStateRef = useRef(countdownState)

  // Polite live region text — set only on state transition
  const [announcement, setAnnouncement] = useState('')

  useEffect(() => {
    if (deadlineMs == null) return

    function recompute(nowMs) {
      const rem = deadlineMs - nowMs
      setRemainingMs(rem)

      const next = deriveState(nowMs, deadlineMs, atRiskMs)
      if (next !== prevStateRef.current) {
        prevStateRef.current = next
        setCountdownState(next)
        // Only announce threshold crossings
        if (next === 'at_risk') setAnnouncement(`${label} at risk`)
        else if (next === 'breached') setAnnouncement(`${label} breached`)
      }
    }

    // Seed immediately so the initial render is correct
    recompute(serverNow())

    return subscribe(recompute)
  }, [deadlineMs, atRiskMs, label])

  if (deadlineMs == null) return null

  const absMs = remainingMs != null ? Math.abs(remainingMs) : 0
  const duration = formatDuration(absMs)

  const stateStyles = {
    normal: {
      color: 'var(--token-text-secondary)',
      icon: '⏱',
      text: `${duration} remaining`,
    },
    at_risk: {
      color: 'var(--token-warning-emphasis)',
      icon: '⚠',
      text: `At risk · ${duration} remaining`,
    },
    breached: {
      color: 'var(--token-danger-emphasis)',
      icon: '⛔',
      text: `Breached · overrun ${duration}`,
    },
  }

  const { color, icon, text } = stateStyles[countdownState]

  return (
    <span
      aria-label={`${label}: ${text}`}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 'var(--token-space-1)',
        fontFamily: 'var(--token-family-mono, var(--token-family-base))',
        fontSize: 'var(--token-fs-13)',
        color,
        fontVariantNumeric: 'tabular-nums',
      }}
    >
      {/* Icon + text encoding — never colour alone (BR-34) */}
      <span aria-hidden="true">{icon}</span>
      <span>{text}</span>

      {/* Polite live region — announces only threshold crossings, not every tick */}
      <span
        role="status"
        aria-live="polite"
        aria-atomic="true"
        style={{
          position: 'absolute',
          width: '1px',
          height: '1px',
          overflow: 'hidden',
          clip: 'rect(0 0 0 0)',
          whiteSpace: 'nowrap',
        }}
      >
        {announcement}
      </span>
    </span>
  )
}
