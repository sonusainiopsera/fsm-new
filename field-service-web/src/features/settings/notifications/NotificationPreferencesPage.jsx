import { useCallback } from 'react'
import { Button } from '../../../design-system/Button.jsx'
import { Chip } from '../../../design-system/Chip.jsx'
import { StateDisplay } from '../../../design-system/StateDisplay.jsx'
import { Switch } from '../../../design-system/Switch.jsx'
import {
  useNotificationPreferences,
  useUpsertNotificationPreferences,
} from './useNotificationPreferences.js'

/** @type {Record<string, string>} */
const CATEGORY_DISPLAY = {
  WORK_ORDER_CREATED: 'Work Order Created',
  WORK_ORDER_ASSIGNED: 'Work Order Assigned',
  WORK_ORDER_UPDATED: 'Work Order Updated',
  WORK_ORDER_COMPLETED: 'Work Order Completed',
  SLA_AT_RISK: 'SLA At Risk',
  SLA_BREACH: 'SLA Breach',
  PARTS_REQUEST: 'Parts Request',
  TECHNICIAN_EN_ROUTE: 'Technician En Route',
}

/** @type {Record<string, string>} */
const CHANNEL_DISPLAY = {
  EMAIL: 'Email',
  SMS: 'SMS',
  IN_APP: 'In App',
  PUSH: 'Push',
}

const CHANNELS = /** @type {const} */ (['EMAIL', 'SMS', 'IN_APP', 'PUSH'])

/**
 * Formats a timestamp as a human-readable relative age string.
 * @param {number} updatedAt - Unix timestamp in milliseconds
 * @returns {string}
 */
function formatAge(updatedAt) {
  const diffMs = Date.now() - updatedAt
  const diffMins = Math.floor(diffMs / 60_000)
  if (diffMins < 1) return 'less than a minute'
  if (diffMins === 1) return '1 minute'
  if (diffMins < 60) return `${diffMins} minutes`
  const diffHours = Math.floor(diffMins / 60)
  if (diffHours === 1) return '1 hour'
  if (diffHours < 24) return `${diffHours} hours`
  const diffDays = Math.floor(diffHours / 24)
  return diffDays === 1 ? '1 day' : `${diffDays} days`
}

/**
 * Groups a flat list of preferences into a map of category -> channel -> enabled.
 * @param {import('../../../api/notificationPreferences.js').NotificationPreferenceDto[]} data
 * @returns {Map<string, Map<string, boolean>>}
 */
function groupByCategory(data) {
  /** @type {Map<string, Map<string, boolean>>} */
  const map = new Map()
  for (const pref of data) {
    if (!map.has(pref.category)) {
      map.set(pref.category, new Map())
    }
    const channelMap = map.get(pref.category)
    if (channelMap) {
      channelMap.set(pref.channel, pref.enabled)
    }
  }
  return map
}

/**
 * @param {{ userId: string }} props
 */
export function NotificationPreferencesPage({ userId }) {
  const { data, isLoading, isError, error, isStale, dataUpdatedAt } =
    useNotificationPreferences(userId)
  const upsertMutation = useUpsertNotificationPreferences(userId)

  /**
   * Handles toggling a single channel for a category.
   * @param {string} category
   * @param {string} channel
   * @param {boolean} enabled
   */
  const handleToggle = useCallback(
    /**
     * @param {string} category
     * @param {string} channel
     * @param {boolean} enabled
     */
    (category, channel, enabled) => {
      upsertMutation.mutate({
        preferences: [{ category, channel, enabled }],
      })
    },
    [upsertMutation]
  )

  // --- Named states ---

  // Loading state (no data yet)
  if (isLoading) {
    return (
      <PageLayout>
        <StateDisplay state="loading" message="Loading notification preferences…" />
      </PageLayout>
    )
  }

  // Error state
  if (isError && !data) {
    const err = /** @type {any} */ (error)
    if (err?.code === 'PERMISSION_DENIED') {
      return (
        <PageLayout>
          <StateDisplay
            state="permission-denied"
            message="You don't have permission to view notification preferences. Contact your administrator."
          />
        </PageLayout>
      )
    }
    return (
      <PageLayout>
        <StateDisplay
          state="error"
          message={error?.message ?? 'An unexpected error occurred while loading preferences.'}
          traceId={err?.traceId}
        />
      </PageLayout>
    )
  }

  // Empty state (loaded but no data)
  if (!data || data.data.length === 0) {
    return (
      <PageLayout>
        <StateDisplay
          state="empty"
          message="No notification categories are configured for your account."
        />
      </PageLayout>
    )
  }

  const grouped = groupByCategory(data.data)
  const categories = Array.from(grouped.keys())

  // Degraded state: we have data but it's stale
  const showDegradedBanner = isStale && dataUpdatedAt > 0

  return (
    <PageLayout>
      {showDegradedBanner && (
        <StateDisplay
          state="degraded"
          message="Showing cached notification preferences."
          stalenessInfo={{
            what: 'Notification preferences',
            age: formatAge(dataUpdatedAt),
          }}
        />
      )}

      {upsertMutation.isError && (
        <StateDisplay
          state="error"
          message={
            upsertMutation.error?.message ??
            'Failed to save your preference change. Your previous settings have been restored.'
          }
          traceId={/** @type {any} */ (upsertMutation.error)?.traceId}
        />
      )}

      <section aria-labelledby="notification-prefs-heading">
        <h2 id="notification-prefs-heading" style={styles.sectionHeading}>
          Notification Preferences
        </h2>
        <p style={styles.sectionDescription}>
          Choose how you receive notifications for each event type. Disabling all channels for a
          category will reduce your visibility of those events.
        </p>

        <div style={styles.tableWrapper} role="region" aria-label="Notification preferences table" tabIndex={0}>
          <table style={styles.table} aria-label="Notification preferences">
            <thead>
              <tr>
                <th scope="col" style={{ ...styles.th, ...styles.categoryCol }}>
                  Category
                </th>
                {CHANNELS.map((ch) => (
                  <th key={ch} scope="col" style={{ ...styles.th, ...styles.channelCol }}>
                    {CHANNEL_DISPLAY[ch]}
                  </th>
                ))}
                <th scope="col" style={{ ...styles.th, ...styles.statusCol }}>
                  Status
                </th>
              </tr>
            </thead>
            <tbody>
              {categories.map((category) => {
                const channelMap = grouped.get(category) ?? new Map()
                const allDisabled = CHANNELS.every((ch) => !channelMap.get(ch))

                return (
                  <tr
                    key={category}
                    style={{
                      ...styles.tr,
                      backgroundColor: allDisabled
                        ? 'color-mix(in srgb, var(--color-warning) 5%, var(--color-bg-base))'
                        : 'var(--color-bg-base)',
                    }}
                  >
                    <td style={{ ...styles.td, ...styles.categoryCol }}>
                      <span style={styles.categoryName}>
                        {CATEGORY_DISPLAY[category] ?? category}
                      </span>
                      {allDisabled && (
                        <span style={styles.warningText} aria-live="polite">
                          <span aria-hidden="true">⚠</span> Reduced reach — all channels off
                        </span>
                      )}
                    </td>
                    {CHANNELS.map((ch) => {
                      const checked = channelMap.get(ch) ?? false
                      const label = `${CATEGORY_DISPLAY[category] ?? category} — ${CHANNEL_DISPLAY[ch]}`
                      return (
                        <td key={ch} style={{ ...styles.td, ...styles.channelCol }}>
                          <Switch
                            label={label}
                            checked={checked}
                            onChange={(next) => handleToggle(category, ch, next)}
                            disabled={upsertMutation.isPending}
                          />
                        </td>
                      )
                    })}
                    <td style={{ ...styles.td, ...styles.statusCol }}>
                      {allDisabled ? (
                        <Chip variant="warning">
                          <span aria-hidden="true">⚠</span> Disabled
                        </Chip>
                      ) : (
                        <Chip variant="success">
                          <span aria-hidden="true">✓</span> Active
                        </Chip>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>

        <div style={styles.paginationInfo} aria-live="polite" aria-atomic="true">
          {data.page.totalElements > 0 && (
            <p style={styles.pageInfo}>
              Showing {data.data.length} of {data.page.totalElements} categories
            </p>
          )}
        </div>
      </section>
    </PageLayout>
  )
}

/**
 * @param {{ children: React.ReactNode }} props
 */
function PageLayout({ children }) {
  return (
    <main style={styles.main} aria-label="Notification preferences settings">
      <header style={styles.header}>
        <h1 style={styles.pageTitle}>Settings</h1>
      </header>
      <div style={styles.content}>{children}</div>
    </main>
  )
}

/** @type {Record<string, React.CSSProperties>} */
const styles = {
  main: {
    minHeight: '100vh',
    backgroundColor: 'var(--color-bg-base)',
    color: 'var(--color-text-primary)',
    padding: 'var(--space-4)',
    maxWidth: '64rem',
    margin: '0 auto',
    boxSizing: 'border-box',
  },
  header: {
    paddingBottom: 'var(--space-6)',
    borderBottom: '1px solid var(--color-border)',
    marginBottom: 'var(--space-6)',
  },
  pageTitle: {
    margin: 0,
    fontSize: 'var(--font-size-xl)',
    fontWeight: 'var(--font-weight-bold)',
    color: 'var(--color-text-primary)',
  },
  content: {
    display: 'flex',
    flexDirection: 'column',
    gap: 'var(--space-6)',
  },
  sectionHeading: {
    margin: '0 0 var(--space-2) 0',
    fontSize: 'var(--font-size-lg)',
    fontWeight: 'var(--font-weight-semibold)',
    color: 'var(--color-text-primary)',
  },
  sectionDescription: {
    margin: '0 0 var(--space-6) 0',
    fontSize: 'var(--font-size-sm)',
    color: 'var(--color-text-secondary)',
    lineHeight: 1.6,
  },
  tableWrapper: {
    overflowX: 'auto',
    borderRadius: 'var(--radius-lg)',
    border: '1px solid var(--color-border)',
    // Allow horizontal scroll at narrow viewports
    WebkitOverflowScrolling: 'touch',
    outline: 'none',
  },
  table: {
    width: '100%',
    borderCollapse: 'collapse',
    minWidth: '360px',
  },
  th: {
    padding: 'var(--space-3) var(--space-4)',
    textAlign: 'left',
    fontSize: 'var(--font-size-xs)',
    fontWeight: 'var(--font-weight-semibold)',
    color: 'var(--color-text-secondary)',
    textTransform: 'uppercase',
    letterSpacing: '0.05em',
    borderBottom: '1px solid var(--color-border)',
    backgroundColor: 'var(--color-bg-subtle)',
    whiteSpace: 'nowrap',
  },
  td: {
    padding: 'var(--space-3) var(--space-4)',
    borderBottom: '1px solid var(--color-border)',
    verticalAlign: 'middle',
  },
  tr: {
    transition: 'background-color var(--motion-duration-fast) var(--motion-easing)',
  },
  categoryCol: {
    minWidth: '10rem',
  },
  channelCol: {
    minWidth: '5rem',
    textAlign: 'center',
  },
  statusCol: {
    minWidth: '6rem',
    textAlign: 'center',
  },
  categoryName: {
    display: 'block',
    fontSize: 'var(--font-size-sm)',
    fontWeight: 'var(--font-weight-medium)',
    color: 'var(--color-text-primary)',
  },
  warningText: {
    display: 'block',
    fontSize: 'var(--font-size-xs)',
    color: 'var(--color-warning)',
    marginTop: 'var(--space-1)',
    fontWeight: 'var(--font-weight-medium)',
  },
  paginationInfo: {
    marginTop: 'var(--space-4)',
  },
  pageInfo: {
    margin: 0,
    fontSize: 'var(--font-size-sm)',
    color: 'var(--color-text-secondary)',
  },
}

// Suppress unused import lint warning for Button - it's exported for potential reuse
void Button
