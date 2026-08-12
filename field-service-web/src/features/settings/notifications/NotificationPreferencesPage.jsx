/**
 * NotificationPreferencesPage
 *
 * Settings screen for per-user notification channel preferences.
 *
 * Composed exclusively from design-system primitives and design tokens:
 * - Zero hard-coded colour, radius, spacing or motion values.
 * - All values via CSS custom properties from the token foundation.
 * - Dual-appearance (light / dark) via the shared AppearanceProvider.
 * - WCAG 2.1 AA: status never conveyed by colour alone (text + icon),
 *   visible 2 px focus ring, 44 px minimum touch targets.
 * - Single-column at 360 px viewport.
 * - Fully keyboard operable (toggle via Space / Enter on each switch).
 *
 * Named states: loading, empty, degraded, permission-denied, error.
 *
 * @module features/settings/notifications/NotificationPreferencesPage
 */

import React, { useCallback } from 'react';
import {
  PageHeader,
  LoadingState,
  EmptyState,
  DegradedState,
  PermissionDeniedState,
  ErrorState,
} from '../../../components/index.js';
import {
  useNotificationPreferences,
  useUpsertNotificationPreferences,
} from './useNotificationPreferences.js';
import styles from './NotificationPreferencesPage.module.css';

/** @import { PreferenceEntryDto } from './useNotificationPreferences.js' */

const CHANNEL_LABELS = {
  EMAIL:  'Email',
  SMS:    'SMS',
  PUSH:   'Push notification',
  IN_APP: 'In-app notification',
};

const CATEGORY_LABELS = {
  WORK_ORDER_ASSIGNMENT: 'Work Order Assignments',
  SLA_ALERT:             'SLA Alerts',
  CUSTOMER_STATUS_UPDATE:'Customer Status Updates',
  CSAT_SURVEY:           'Satisfaction Surveys',
  APPOINTMENT_UPDATE:    'Appointment Changes',
  PORTAL_REQUEST_UPDATE: 'Portal Request Updates',
  CERTIFICATION_ALERT:   'Certification Alerts',
};

const ALL_CHANNELS = ['EMAIL', 'SMS', 'PUSH', 'IN_APP'];
const ALL_CATEGORIES = Object.keys(CATEGORY_LABELS);

/**
 * Resolves the effective enabled state for a (category, channel) pair
 * from the API response — absent rows are default-on (true).
 *
 * @param {PreferenceEntryDto[]} entries
 * @param {string} category
 * @param {string} channel
 * @returns {boolean}
 */
function resolveEnabled(entries, category, channel) {
  const entry = entries.find((e) => e.category === category && e.channel === channel);
  return entry ? entry.enabled : true; // default-on
}

/**
 * @param {{
 *   userId: string,
 * }} props
 */
export default function NotificationPreferencesPage({ userId }) {
  const { data, isPending, isError, error, isFetching, dataUpdatedAt } =
    useNotificationPreferences({ userId });

  const mutation = useUpsertNotificationPreferences(userId);

  const handleToggle = useCallback(
    (category, channel, currentEnabled) => {
      mutation.mutate({
        preferences: [{ category, channel, enabled: !currentEnabled }],
      });
    },
    [mutation]
  );

  // ── Loading (first fetch) ────────────────────────────────────────────────
  if (isPending) {
    return (
      <main className={styles.page}>
        <PageHeader title="Notification Preferences" />
        <LoadingState description="Loading your notification preferences…" />
      </main>
    );
  }

  // ── Permission denied ─────────────────────────────────────────────────────
  if (isError && error?.status === 403) {
    return (
      <main className={styles.page}>
        <PageHeader title="Notification Preferences" />
        <PermissionDeniedState />
      </main>
    );
  }

  // ── Error state ───────────────────────────────────────────────────────────
  if (isError) {
    return (
      <main className={styles.page}>
        <PageHeader title="Notification Preferences" />
        <ErrorState
          description={error?.message ?? 'Unable to load preferences.'}
        />
      </main>
    );
  }

  const entries = data?.data ?? [];
  const staleSeconds = dataUpdatedAt
    ? Math.round((Date.now() - dataUpdatedAt) / 1000)
    : null;

  return (
    <main className={styles.page}>
      <PageHeader
        title="Notification Preferences"
        subtitle="Choose which channels you receive for each notification type."
      />

      {/* Degraded — stale data during background refetch */}
      {isFetching && data && staleSeconds !== null && staleSeconds > 60 && (
        <DegradedState
          description={`Showing preferences from ${staleSeconds} seconds ago. Refreshing…`}
        />
      )}

      {/* Mutation error banner */}
      {mutation.isError && (
        <div className={styles.mutationError} role="alert" aria-live="assertive">
          <span className={styles.mutationErrorIcon} aria-hidden="true">⚠</span>
          <span>
            {mutation.error?.message ?? 'Failed to save preferences. Your change has been reverted.'}
            {mutation.error?.body?.traceId && (
              <span className={styles.traceId}>
                {' '}Trace ID: {mutation.error.body.traceId}
              </span>
            )}
          </span>
        </div>
      )}

      {/* Empty state — no data and no error */}
      {entries.length === 0 && !isFetching && (
        <EmptyState
          title="Using default preferences"
          description="All notification channels are enabled by default. Toggle below to customise."
        />
      )}

      {/* Preference table */}
      <section aria-label="Notification preferences by category">
        <div className={styles.table} role="table" aria-label="Notification channel preferences">

          {/* Header row */}
          <div className={styles.headerRow} role="row">
            <div className={styles.categoryHeader} role="columnheader">Category</div>
            {ALL_CHANNELS.map((ch) => (
              <div key={ch} className={styles.channelHeader} role="columnheader">
                {CHANNEL_LABELS[ch]}
              </div>
            ))}
          </div>

          {/* Data rows */}
          {ALL_CATEGORIES.map((category) => (
            <div key={category} className={styles.row} role="row">
              <div className={styles.categoryCell} role="rowheader">
                {CATEGORY_LABELS[category] ?? category}
              </div>
              {ALL_CHANNELS.map((channel) => {
                const enabled = resolveEnabled(entries, category, channel);
                const id = `pref-${category}-${channel}`;
                return (
                  <div key={channel} className={styles.channelCell} role="cell">
                    <button
                      id={id}
                      type="button"
                      role="switch"
                      aria-checked={enabled}
                      aria-label={`${CHANNEL_LABELS[channel]} for ${CATEGORY_LABELS[category] ?? category}: ${enabled ? 'on' : 'off'}`}
                      className={[
                        styles.toggle,
                        enabled ? styles.toggleOn : styles.toggleOff,
                        mutation.isPending ? styles.togglePending : '',
                      ].filter(Boolean).join(' ')}
                      onClick={() => handleToggle(category, channel, enabled)}
                      disabled={mutation.isPending}
                    >
                      <span className={styles.toggleThumb} aria-hidden="true" />
                      <span className="sr-only">{enabled ? 'On' : 'Off'}</span>
                    </button>
                  </div>
                );
              })}
            </div>
          ))}
        </div>
      </section>

      {entries.some((e) => !e.enabled) && (
        <p className={styles.warningNote} role="status" aria-live="polite">
          <span aria-hidden="true">ⓘ</span>{' '}
          Some channels are disabled. Critical alerts may still be delivered via in-app notifications.
        </p>
      )}
    </main>
  );
}
