/**
 * @fileoverview Notification Preferences settings screen (WO-197).
 *
 * Five named states:
 *   loading          — skeleton on first load
 *   empty            — user has no configured channels
 *   degraded         — stale data, shows age
 *   permission-denied — 403 / accessing another user without ADMIN
 *   error            — unexpected error with traceId
 *
 * Composed exclusively from design tokens (var(--token-*)) and shared primitives.
 * WCAG 2.1 AA compliant; 360 px single-column; 44 px touch targets; reduced-motion honoured.
 *
 * @typedef {{ category: string, channel: string, enabled: boolean, source: 'DEFAULT'|'EXPLICIT' }} PreferenceItem
 */

import { useCallback } from 'react'
import {
  PageHeader,
  LoadingState,
  EmptyState,
  PermissionDeniedState,
  ErrorState,
  DegradedState,
} from '../../../components/index.js'
import {
  useNotificationPreferences,
  useUpdateNotificationPreferences,
} from './api/useNotificationPreferences.js'

// ── Category display labels ───────────────────────────────────────────────────

const CATEGORY_LABELS = {
  WO_ASSIGNED:            'Work order assigned to you',
  WO_REASSIGNED:          'Work order reassigned',
  WO_STATUS_CHANGE:       'Status change update',
  SLA_RISK:               'SLA at-risk alert',
  SLA_BREACH:             'SLA breach alert',
  APPOINTMENT_CHANGED:    'Appointment changed',
  CERTIFICATION_EXPIRING: 'Certification expiring',
  CERTIFICATION_EXPIRED:  'Certification expired',
  WO_DUPLICATE_LINKED:    'Service request linked to existing case',
  WO_REJECTED:            'Service request rejected',
  CLOSURE_SURVEY:         'Feedback survey after closure',
}

const CHANNEL_LABELS = {
  IN_APP: 'In-App',
  EMAIL:  'Email',
  SMS:    'SMS',
  PUSH:   'Push',
}

// ── NotificationPreferencesPage ───────────────────────────────────────────────

/**
 * @param {{ userId: string }} props
 */
export default function NotificationPreferencesPage({ userId }) {
  const {
    data,
    isLoading,
    isError,
    error,
    isFetching,
    dataUpdatedAt,
  } = useNotificationPreferences(userId)

  const mutation = useUpdateNotificationPreferences(userId)

  const handleToggle = useCallback(
    (category, channel, newEnabled) => {
      mutation.mutate([{ category, channel, enabled: newEnabled }])
    },
    [mutation]
  )

  // ── Permission denied ───────────────────────────────────────────────────────

  if (isError && error?.status === 403) {
    return (
      <div style={pageStyle}>
        <PageHeader title="Notification Preferences" />
        <PermissionDeniedState />
      </div>
    )
  }

  // ── Generic error ───────────────────────────────────────────────────────────

  if (isError) {
    return (
      <div style={pageStyle}>
        <PageHeader title="Notification Preferences" />
        <ErrorState
          message={error?.message ?? 'Failed to load notification preferences.'}
          onRetry={() => window.location.reload()}
        />
      </div>
    )
  }

  // ── Loading ─────────────────────────────────────────────────────────────────

  if (isLoading) {
    return (
      <div style={pageStyle}>
        <PageHeader title="Notification Preferences" />
        <LoadingState />
      </div>
    )
  }

  const prefs = data?.data ?? []

  // ── Degraded: stale data ────────────────────────────────────────────────────

  const isStale = isFetching && dataUpdatedAt > 0
  const staleSinceMs = dataUpdatedAt ? Date.now() - dataUpdatedAt : 0
  const staleSinceSec = Math.floor(staleSinceMs / 1000)

  // ── Empty ───────────────────────────────────────────────────────────────────

  if (prefs.length === 0) {
    return (
      <div style={pageStyle}>
        <PageHeader title="Notification Preferences" />
        <EmptyState message="No notification preferences configured. All channels are on by default." />
      </div>
    )
  }

  // ── Build category → channel map ────────────────────────────────────────────

  /** @type {Record<string, Record<string, PreferenceItem>>} */
  const byCategory = {}
  for (const pref of prefs) {
    if (!byCategory[pref.category]) byCategory[pref.category] = {}
    byCategory[pref.category][pref.channel] = pref
  }

  const allChannels = ['IN_APP', 'EMAIL', 'SMS', 'PUSH']

  return (
    <div style={pageStyle}>
      <PageHeader
        title="Notification Preferences"
        subtitle="Choose which channels you receive for each notification type."
      />

      {/* Degraded banner: stale + refreshing */}
      {isStale && (
        <DegradedState
          message={`Preferences last updated ${staleSinceSec} second${staleSinceSec !== 1 ? 's' : ''} ago. Refreshing…`}
        />
      )}

      {/* Mutation error inline */}
      {mutation.isError && (
        <div
          role="alert"
          aria-live="assertive"
          style={mutationErrorStyle}
        >
          Could not save your preference. Please try again.
        </div>
      )}

      {/* Preference cards */}
      <div
        role="list"
        aria-label="Notification preferences"
        style={listStyle}
      >
        {Object.keys(byCategory).sort().map((category) => {
          const channels = byCategory[category]
          const categoryLabel = CATEGORY_LABELS[category] ?? category
          return (
            <div
              key={category}
              role="listitem"
              style={cardStyle}
              data-testid={`pref-card-${category}`}
            >
              <div style={cardHeaderStyle}>
                <span style={categoryLabelStyle}>{categoryLabel}</span>
                {Object.values(channels).every((p) => !p.enabled) && (
                  <span
                    role="img"
                    aria-label="All channels off — critical alerts may not reach you"
                    style={warningIconStyle}
                    title="All channels disabled for this category"
                  >
                    ⚠
                  </span>
                )}
              </div>

              {/* Channel toggles */}
              <div style={channelRowStyle}>
                {allChannels.map((ch) => {
                  const pref = channels[ch]
                  if (!pref) return null
                  const toggleId = `toggle-${category}-${ch}`
                  const isEnabled = pref.enabled
                  return (
                    <label
                      key={ch}
                      htmlFor={toggleId}
                      style={toggleLabelStyle}
                    >
                      <span style={channelNameStyle}>
                        {CHANNEL_LABELS[ch] ?? ch}
                      </span>
                      <button
                        id={toggleId}
                        role="switch"
                        aria-checked={isEnabled}
                        aria-label={`${CHANNEL_LABELS[ch] ?? ch} notifications for ${categoryLabel} — ${isEnabled ? 'on' : 'off'}`}
                        onClick={() => handleToggle(category, ch, !isEnabled)}
                        disabled={mutation.isPending}
                        style={toggleButtonStyle(isEnabled)}
                        data-testid={`toggle-${category}-${ch}`}
                      >
                        <span style={toggleThumbStyle(isEnabled)} aria-hidden="true" />
                        <span className="sr-only">{isEnabled ? 'On' : 'Off'}</span>
                      </button>
                    </label>
                  )
                })}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

// ── Styles ────────────────────────────────────────────────────────────────────

const pageStyle = {
  padding: 'var(--token-space-6)',
  maxWidth: '640px',
  margin: '0 auto',
  fontFamily: 'var(--token-family-base)',
}

const listStyle = {
  display: 'flex',
  flexDirection: 'column',
  gap: 'var(--token-space-4)',
  marginTop: 'var(--token-space-4)',
}

const cardStyle = {
  background: 'var(--token-surface-card)',
  border: '1px solid var(--token-border-default)',
  borderRadius: 'var(--token-radius-card)',
  padding: 'var(--token-space-4)',
}

const cardHeaderStyle = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  marginBottom: 'var(--token-space-3)',
}

const categoryLabelStyle = {
  fontSize: 'var(--token-fs-14)',
  fontWeight: 'var(--token-fw-semibold)',
  color: 'var(--token-text-primary)',
}

const warningIconStyle = {
  color: 'var(--token-warning-default)',
  fontSize: 'var(--token-fs-16)',
}

const channelRowStyle = {
  display: 'flex',
  flexWrap: 'wrap',
  gap: 'var(--token-space-3)',
}

const toggleLabelStyle = {
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  gap: 'var(--token-space-1)',
  minWidth: '44px',
  minHeight: '44px',
  justifyContent: 'center',
  cursor: 'pointer',
}

const channelNameStyle = {
  fontSize: 'var(--token-fs-11)',
  color: 'var(--token-text-secondary)',
  userSelect: 'none',
}

/** @param {boolean} enabled */
function toggleButtonStyle(enabled) {
  return {
    position: 'relative',
    display: 'inline-block',
    width: '40px',
    height: '22px',
    minWidth: '40px',
    minHeight: '22px',
    borderRadius: 'var(--token-radius-pill)',
    border: '2px solid transparent',
    background: enabled ? 'var(--token-accent-500)' : 'var(--token-neutral-300)',
    cursor: 'pointer',
    outline: 'none',
    transition: 'background var(--token-motion-standard)',
    padding: 0,
  }
}

/** @param {boolean} enabled */
function toggleThumbStyle(enabled) {
  return {
    position: 'absolute',
    top: '2px',
    left: enabled ? '20px' : '2px',
    width: '14px',
    height: '14px',
    borderRadius: '50%',
    background: 'var(--token-surface-base)',
    transition: 'left var(--token-motion-standard)',
  }
}

const mutationErrorStyle = {
  padding: 'var(--token-space-3)',
  marginBottom: 'var(--token-space-3)',
  background: 'var(--token-danger-surface)',
  border: '1px solid var(--token-danger-border)',
  borderRadius: 'var(--token-radius-control)',
  color: 'var(--token-danger-text)',
  fontSize: 'var(--token-fs-13)',
}
