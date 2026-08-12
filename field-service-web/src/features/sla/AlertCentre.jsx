/**
 * @fileoverview AlertCentre — panel listing open at-risk and breached work orders.
 *
 * Data is driven by TanStack Query polling /api/v1/sla-alerts/open.
 * SSE events (from useSlaAlertStream) invalidate the ['slaAlerts'] key so the
 * panel reconciles with server state on each event rather than trusting payload.
 *
 * Sorted by urgency: breached first, then at_risk, then by minutesRemaining asc.
 * When stream is stale, each row shows an out-of-date affordance.
 */

import { useQuery } from '@tanstack/react-query'
import { get } from '../../api/http.js'
import { SlaRiskChip } from './SlaRiskChip.jsx'

/**
 * Sort comparator — breached before at_risk, then by minutesRemaining ascending.
 * @param {object} a
 * @param {object} b
 * @returns {number}
 */
function urgencySort(a, b) {
  const riskOrder = { breached: 0, at_risk: 1, healthy: 2 }
  const ra = riskOrder[a.riskLevel] ?? 2
  const rb = riskOrder[b.riskLevel] ?? 2
  if (ra !== rb) return ra - rb
  const ma = a.minutesRemaining ?? Infinity
  const mb = b.minutesRemaining ?? Infinity
  return ma - mb
}

/**
 * @param {{
 *   streamStatus: 'live' | 'reconnecting' | 'stale',
 *   onAttributeBreach?: (workOrderId: string) => void,
 *   onNavigate?: (workOrderId: string) => void,
 * }} props
 */
export function AlertCentre({ streamStatus, onAttributeBreach, onNavigate }) {
  const isStale = streamStatus === 'stale'

  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['slaAlerts'],
    queryFn: () => get('/sla-alerts/open'),
    staleTime: 30_000,
    refetchInterval: streamStatus === 'stale' ? 15_000 : false,
  })

  const alerts = Array.isArray(data?.alerts) ? [...data.alerts].sort(urgencySort) : []

  const containerStyle = {
    background: 'var(--token-surface-card)',
    border: '1px solid var(--token-border-default)',
    borderRadius: 'var(--token-radius-md)',
    overflow: 'hidden',
  }

  const headerStyle = {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: 'var(--token-space-3) var(--token-space-4)',
    borderBottom: '1px solid var(--token-border-default)',
    background: 'var(--token-surface-raised)',
  }

  const headerTitleStyle = {
    fontFamily: 'var(--token-family-base)',
    fontSize: 'var(--token-fs-14)',
    fontWeight: 600,
    color: 'var(--token-text-primary)',
    display: 'flex',
    alignItems: 'center',
    gap: 'var(--token-space-2)',
  }

  return (
    <section aria-label="SLA Alert Centre" style={containerStyle}>
      <div style={headerStyle}>
        <h2 style={headerTitleStyle}>
          <span aria-hidden="true">⚑</span>
          Alert Centre
          {alerts.length > 0 && (
            <span
              aria-label={`${alerts.length} open alerts`}
              style={{
                background: 'var(--token-danger-default)',
                color: 'var(--token-danger-on)',
                borderRadius: 'var(--token-radius-pill)',
                fontSize: 'var(--token-fs-11)',
                padding: '1px 6px',
                fontWeight: 700,
              }}
            >
              {alerts.length}
            </span>
          )}
        </h2>
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--token-space-2)' }}>
          {isStale && (
            <span
              role="status"
              aria-live="polite"
              style={{
                fontSize: 'var(--token-fs-12)',
                color: 'var(--token-warning-emphasis)',
                display: 'flex',
                alignItems: 'center',
                gap: 'var(--token-space-1)',
              }}
            >
              <span aria-hidden="true">⏷</span>
              Data may be out of date
            </span>
          )}
          {(isStale || isError) && (
            <button
              type="button"
              aria-label="Refresh alerts"
              onClick={() => refetch()}
              style={{
                padding: 'var(--token-space-1) var(--token-space-2)',
                fontSize: 'var(--token-fs-12)',
                border: '1px solid var(--token-border-default)',
                borderRadius: 'var(--token-radius-sm)',
                background: 'var(--token-surface-card)',
                cursor: 'pointer',
                color: 'var(--token-text-secondary)',
                minHeight: 44,
                minWidth: 44,
              }}
            >
              ↺ Refresh
            </button>
          )}
        </div>
      </div>

      {isLoading && (
        <div
          role="status"
          aria-label="Loading alerts"
          style={{ padding: 'var(--token-space-6)', textAlign: 'center', color: 'var(--token-text-secondary)' }}
        >
          Loading alerts…
        </div>
      )}

      {isError && !isLoading && (
        <div
          role="alert"
          style={{ padding: 'var(--token-space-4)', textAlign: 'center', color: 'var(--token-danger-emphasis)' }}
        >
          Failed to load alerts.{' '}
          <button
            type="button"
            onClick={() => refetch()}
            style={{ color: 'var(--token-accent-700)', background: 'none', border: 'none', cursor: 'pointer' }}
          >
            Retry
          </button>
        </div>
      )}

      {!isLoading && !isError && alerts.length === 0 && (
        <div
          style={{
            padding: 'var(--token-space-8)',
            textAlign: 'center',
            color: 'var(--token-text-secondary)',
            fontSize: 'var(--token-fs-13)',
          }}
        >
          <div style={{ fontSize: '2rem', marginBottom: 'var(--token-space-2)' }} aria-hidden="true">✓</div>
          <div>No open alerts — all SLAs are on track</div>
        </div>
      )}

      {!isLoading && alerts.length > 0 && (
        <ul
          aria-label="Open SLA alerts"
          style={{ listStyle: 'none', margin: 0, padding: 0 }}
        >
          {alerts.map((alert) => (
            <AlertRow
              key={alert.workOrderId}
              alert={alert}
              stale={isStale}
              onAttributeBreach={onAttributeBreach}
              onNavigate={onNavigate}
            />
          ))}
        </ul>
      )}
    </section>
  )
}

/**
 * @param {{
 *   alert: {
 *     workOrderId: string,
 *     reference?: string,
 *     title?: string,
 *     priority?: string,
 *     state?: string,
 *     riskLevel: 'healthy' | 'at_risk' | 'breached',
 *     minutesRemaining?: number | null,
 *     triggerReason?: string,
 *     projectionBasis?: string,
 *   },
 *   stale: boolean,
 *   onAttributeBreach?: (id: string) => void,
 *   onNavigate?: (id: string) => void,
 * }} props
 */
function AlertRow({ alert, stale, onAttributeBreach, onNavigate }) {
  const rowStyle = {
    display: 'flex',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    padding: 'var(--token-space-3) var(--token-space-4)',
    borderBottom: '1px solid var(--token-border-subtle)',
    gap: 'var(--token-space-3)',
    background: stale ? 'var(--token-neutral-50)' : 'transparent',
  }

  const metaStyle = {
    fontSize: 'var(--token-fs-12)',
    color: 'var(--token-text-secondary)',
    marginTop: 'var(--token-space-1)',
    display: 'flex',
    flexWrap: 'wrap',
    gap: 'var(--token-space-2)',
  }

  return (
    <li style={rowStyle} data-testid={`alert-row-${alert.workOrderId}`}>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--token-space-2)', flexWrap: 'wrap' }}>
          {onNavigate ? (
            <button
              type="button"
              onClick={() => onNavigate(alert.workOrderId)}
              aria-label={`Open work order ${alert.reference ?? alert.workOrderId}`}
              style={{
                background: 'none',
                border: 'none',
                cursor: 'pointer',
                color: 'var(--token-accent-700)',
                fontSize: 'var(--token-fs-13)',
                fontWeight: 600,
                padding: 0,
                textAlign: 'left',
              }}
            >
              {alert.reference ?? alert.workOrderId}
            </button>
          ) : (
            <span style={{ fontSize: 'var(--token-fs-13)', fontWeight: 600, color: 'var(--token-text-primary)' }}>
              {alert.reference ?? alert.workOrderId}
            </span>
          )}
          {alert.title && (
            <span
              style={{
                fontSize: 'var(--token-fs-13)',
                color: 'var(--token-text-primary)',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
                maxWidth: 240,
              }}
              title={alert.title}
            >
              {alert.title}
            </span>
          )}
        </div>
        <div style={metaStyle}>
          {alert.priority && (
            <span>
              <span aria-hidden="true" style={{ fontWeight: 600 }}>Priority:</span> {alert.priority}
            </span>
          )}
          {alert.state && (
            <span>
              <span aria-hidden="true" style={{ fontWeight: 600 }}>State:</span> {alert.state}
            </span>
          )}
          {alert.triggerReason && (
            <span
              title={alert.triggerReason}
              style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 200 }}
            >
              <span aria-hidden="true" style={{ fontWeight: 600 }}>Reason:</span>{' '}
              {alert.triggerReason}
            </span>
          )}
          {alert.projectionBasis && (
            <span
              title={alert.projectionBasis}
              style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 200 }}
            >
              <span aria-hidden="true" style={{ fontWeight: 600 }}>Basis:</span>{' '}
              {alert.projectionBasis}
            </span>
          )}
        </div>
      </div>
      <div style={{ flexShrink: 0 }}>
        <SlaRiskChip
          riskLevel={alert.riskLevel}
          minutesRemaining={alert.minutesRemaining}
          stale={stale}
          size="sm"
          onAttribute={alert.riskLevel === 'breached' && onAttributeBreach
            ? () => onAttributeBreach(alert.workOrderId)
            : undefined}
        />
      </div>
    </li>
  )
}
