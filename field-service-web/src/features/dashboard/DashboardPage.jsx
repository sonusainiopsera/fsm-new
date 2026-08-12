/**
 * @fileoverview DashboardPage — operations manager KPI widget grid (WO-167).
 *
 * Renders a responsive grid of KPI cards backed by a single batched
 * conditional-poll query (30 s ETag) against the WO-166 widget endpoint.
 *
 * Four named states per widget:
 *   loading   — skeleton placeholder
 *   empty     — zero sampleCount with explanatory message
 *   degraded  — last-known value + stale/data-unavailable label + data age
 *   error     — per-widget error state with retry affordance
 *
 * The grid renders siblings independently; one failing widget never blanks
 * the others.
 */

import { Suspense } from 'react'
import { useNavigate } from 'react-router-dom'
import { PageHeader, KpiCard, LoadingState, EmptyState, DegradedState, ErrorState } from '../../components/index.js'
import { useDashboardWidgets } from './api/useDashboardWidgets.js'
import { DataAgeBadge } from './components/DataAgeBadge.jsx'
import { MetricChart } from './components/MetricChart.jsx'
import { WindowSelector, SegmentFilter, useWindowParam, useSegmentParam } from './components/WindowSelector.jsx'

// ── Widget catalogue ──────────────────────────────────────────────────────────

/** Human-readable label for each server MetricKey. */
const METRIC_LABELS = {
  SLA_COMPLIANCE_RATE:   'SLA Compliance',
  SLA_RESOLUTION_MEAN:   'Resolution Time (Mean)',
  SLA_RESOLUTION_MEDIAN: 'Resolution Time (Median)',
  SLA_BREACH_COUNT:      'SLA Breaches',
  FTF_RATE:              'First-Time Fix',
  REPEAT_VISIT_COUNT:    'Repeat Visits',
  BACKLOG_OPEN_COUNT:    'Open Backlog',
  BACKLOG_ON_HOLD_COUNT: 'On-Hold Backlog',
  UTILIZATION_RATE:      'Technician Utilisation',
  JOBS_PER_DAY:          'Jobs / Day',
  WORKLOAD_BALANCE_CV:   'Workload Balance (CV)',
}

/**
 * For percentage metrics the raw value is 0–1; multiply by 100 for display.
 * @param {import('./api/useDashboardWidgets.js').WidgetDto} w
 * @returns {{ display: string, raw: number | null }}
 */
function formatValue(w) {
  if (w.value == null) return { display: '—', raw: null }
  const isPct = w.unit === '%'
  const raw = isPct ? w.value * 100 : w.value
  if (isPct) return { display: `${raw.toFixed(1)}`, raw }
  if (w.unit === 'minutes') return { display: raw >= 60 ? `${(raw / 60).toFixed(1)}h` : `${Math.round(raw)}`, raw }
  if (Number.isInteger(raw)) return { display: String(raw), raw }
  return { display: raw.toFixed(2), raw }
}

/**
 * Derives the display unit label.
 * @param {import('./api/useDashboardWidgets.js').WidgetDto} w
 * @returns {string}
 */
function displayUnit(w) {
  if (w.unit === '%') return '%'
  if (w.unit === 'minutes') return 'h'
  if (w.unit === 'count') return ''
  return w.unit ?? ''
}

/**
 * Converts a percent-scale delta from the raw DTO to a display number.
 * The server supplies deltaVsPriorPeriod as a percentage-point change already.
 *
 * @param {number | null} delta
 * @returns {number | null}
 */
function normaliseDelta(delta) {
  return delta ?? null
}

/**
 * Returns numeric targetAttainment × 100 if it's a number, else null.
 * Callers check for 'BASELINE_PENDING' separately.
 *
 * @param {number | 'BASELINE_PENDING' | null} ta
 * @returns {number | null}
 */
function resolveTargetAttainment(ta) {
  if (ta === 'BASELINE_PENDING' || ta == null) return null
  return typeof ta === 'number' ? ta * 100 : null
}

// ── WidgetCard — single card state machine ────────────────────────────────────

/**
 * @param {{
 *   widget: import('./api/useDashboardWidgets.js').WidgetDto,
 *   isLoading: boolean,
 *   onRetry?: () => void,
 *   onDrillDown?: (metricKey: string) => void
 * }} props
 */
function WidgetCard({ widget, isLoading, onRetry, onDrillDown }) {
  const label = METRIC_LABELS[widget.metricKey] ?? widget.metricKey

  // Loading state
  if (isLoading && !widget) {
    return (
      <article data-metric-key="loading" style={cardShell}>
        <LoadingState />
      </article>
    )
  }

  const { display, raw } = formatValue(widget)
  const unit = displayUnit(widget)
  const delta = normaliseDelta(widget.deltaVsPriorPeriod)
  const targetPct = resolveTargetAttainment(widget.targetAttainment)
  const isBaselinePending = widget.targetAttainment === 'BASELINE_PENDING'
  const isProvisional = widget.maturity === 'PROVISIONAL'
  const isEmpty = widget.sampleCount === 0 && !widget.degraded

  // Empty state — no data to display
  if (isEmpty) {
    return (
      <article data-metric-key={widget.metricKey} style={cardShell}>
        <span style={labelStyle}>{label}</span>
        <EmptyState message={`No ${label.toLowerCase()} data for this period.`} />
      </article>
    )
  }

  // Degraded state — last known value + explicit data-unavailable label
  if (widget.degraded) {
    const reasonLabel = DEGRADED_REASONS[widget.degradedReason] ?? 'Data temporarily unavailable'
    return (
      <article
        data-metric-key={widget.metricKey}
        data-degraded="true"
        style={cardShell}
        aria-label={`${label}: degraded — ${reasonLabel}`}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 'var(--token-space-2)' }}>
          <span style={labelStyle}>{label}</span>
          <DataAgeBadge
            dataAsOf={widget.dataAsOf}
            stalenessSeconds={widget.stalenessSeconds}
            degraded
          />
        </div>

        {widget.value != null ? (
          <>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 'var(--token-space-2)' }}>
              <span style={valueStyle} aria-label={`${label}: ${display}${unit ? ' ' + unit : ''} (last known value)`}>
                {display}
              </span>
              {unit && <span style={{ fontSize: 'var(--token-fs-16)', color: 'var(--token-text-secondary)' }}>{unit}</span>}
            </div>
            <span
              role="status"
              aria-live="polite"
              style={{
                display: 'block',
                marginTop: 'var(--token-space-1)',
                fontSize: 'var(--token-fs-12)',
                color: 'var(--token-warning-emphasis)',
                fontWeight: 500,
              }}
            >
              ⚠ {reasonLabel}
            </span>
          </>
        ) : (
          <EmptyState message={reasonLabel} />
        )}

        {onRetry && (
          <button
            type="button"
            onClick={onRetry}
            style={retryBtnStyle}
            aria-label={`Retry loading ${label}`}
          >
            Retry
          </button>
        )}
      </article>
    )
  }

  // Normal state — full KpiCard anatomy
  return (
    <div
      data-metric-key={widget.metricKey}
      style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-2)' }}
    >
      {onDrillDown && (
        <button
          type="button"
          aria-label={`View work orders for ${label}`}
          data-testid={`drill-down-${widget.metricKey}`}
          onClick={() => onDrillDown(widget.metricKey)}
          style={drillDownBtnStyle}
        >
          View work orders →
        </button>
      )}
      <KpiCard
        label={label}
        value={display}
        unit={unit || undefined}
        delta={delta}
        deltaPeriodLabel="vs prior period"
        target={targetPct != null ? 100 : undefined}
        currentRaw={targetPct}
        sparkline={widget.trend?.length >= 2 ? widget.trend : null}
      />

      {/* Badges rendered beneath KpiCard */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--token-space-2)', paddingInline: 'var(--token-space-4)' }}>
        {isProvisional && (
          <span
            data-maturity="PROVISIONAL"
            aria-label="Provisional: this metric is based on limited data"
            style={maturityBadgeStyle}
          >
            PROVISIONAL
          </span>
        )}
        {isBaselinePending && (
          <span
            data-target-attainment="BASELINE_PENDING"
            aria-label="Baseline pending: target not yet established"
            style={baselinePendingStyle}
          >
            BASELINE PENDING
          </span>
        )}
        <DataAgeBadge
          dataAsOf={widget.dataAsOf}
          stalenessSeconds={widget.stalenessSeconds}
        />
      </div>

      {/* Expanded trend chart for non-sparkline view */}
      {widget.trend?.length >= 2 && (
        <div style={{ paddingInline: 'var(--token-space-4)' }}>
          <MetricChart
            id={`chart-${widget.metricKey}`}
            label={label}
            points={widget.trend}
            unit={unit || undefined}
          />
        </div>
      )}
    </div>
  )
}

// ── DashboardPage ─────────────────────────────────────────────────────────────

/**
 * @param {{ window?: string, segment?: string }} props
 */
export default function DashboardPage() {
  const navigate = useNavigate()
  const { window, setWindow } = useWindowParam()
  const { segment } = useSegmentParam()

  function handleDrillDown(metricKey) {
    const params = new URLSearchParams()
    params.set('metric', metricKey)
    params.set('window', window)
    if (segment && segment !== 'ALL') params.set('segment', segment)
    navigate(`drill-down?${params.toString()}`)
  }

  const { data, isLoading, isError, error, refetch, isFetching } = useDashboardWidgets({ window, segment })

  const widgets = data?.data ?? []
  const allDegraded = widgets.length > 0 && widgets.every(w => w.degraded)

  // Full-page loading skeleton on first load
  if (isLoading) {
    return (
      <div style={pageStyle}>
        <PageHeader title="Operations Dashboard" subtitle="KPI performance overview" />
        <LoadingState />
      </div>
    )
  }

  // Authorisation / hard error
  if (isError) {
    const msg = error?.code === 'FORBIDDEN'
      ? 'You do not have permission to view the operations dashboard.'
      : error?.message ?? 'Failed to load dashboard data.'
    return (
      <div style={pageStyle}>
        <PageHeader title="Operations Dashboard" subtitle="KPI performance overview" />
        <ErrorState message={msg} onRetry={() => refetch()} />
      </div>
    )
  }

  return (
    <div style={pageStyle}>
      <PageHeader title="Operations Dashboard" subtitle="KPI performance overview" />

      {/* Controls row */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 'var(--token-space-4)',
          marginBottom: 'var(--token-space-6)',
          flexWrap: 'wrap',
        }}
      >
        <WindowSelector window={window} onChange={setWindow} />

        <SegmentFilter segment={segment} onChange={setSegment} />

        {isFetching && (
          <span
            role="status"
            aria-live="polite"
            style={{ fontSize: 'var(--token-fs-12)', color: 'var(--token-text-secondary)' }}
          >
            Refreshing…
          </span>
        )}
      </div>

      {/* Dashboard-level degraded banner when every widget is stale */}
      {allDegraded && (
        <div
          role="alert"
          aria-live="polite"
          style={{
            marginBottom: 'var(--token-space-4)',
            padding: 'var(--token-space-3) var(--token-space-4)',
            borderRadius: 'var(--token-radius-card)',
            background: 'var(--token-warning-subtle)',
            border: '1px solid var(--token-warning-default)',
            color: 'var(--token-warning-emphasis)',
            fontSize: 'var(--token-fs-14)',
            display: 'flex',
            alignItems: 'center',
            gap: 'var(--token-space-2)',
          }}
        >
          <span aria-hidden="true">⚠</span>
          Live data is temporarily unavailable. All widgets show last known values.
        </div>
      )}

      {/* Empty dashboard */}
      {widgets.length === 0 && (
        <EmptyState message="No KPI data available for the selected window and segment." />
      )}

      {/* Widget grid */}
      {widgets.length > 0 && (
        <div
          data-testid="widget-grid"
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))',
            gap: 'var(--token-space-4)',
          }}
        >
          {widgets.map(widget => (
            <WidgetCard
              key={widget.metricKey}
              widget={widget}
              isLoading={isLoading}
              onRetry={() => refetch()}
              onDrillDown={handleDrillDown}
            />
          ))}
        </div>
      )}
    </div>
  )
}

// ── Loading skeleton grid ─────────────────────────────────────────────────────

/**
 * Exported for use in Suspense fallback if needed.
 */
export function DashboardSkeleton() {
  return (
    <div style={pageStyle}>
      <PageHeader title="Operations Dashboard" subtitle="KPI performance overview" />
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 'var(--token-space-4)' }}>
        {Array.from({ length: 6 }).map((_, i) => (
          <div
            key={i}
            aria-hidden="true"
            style={{
              height: '160px',
              borderRadius: 'var(--token-radius-card)',
              background: 'var(--token-neutral-100)',
              animation: 'skeleton-pulse 1.5s ease-in-out infinite',
            }}
          />
        ))}
      </div>
    </div>
  )
}

// ── Style tokens ──────────────────────────────────────────────────────────────

const DEGRADED_REASONS = {
  READ_MODEL_STALE:         'Analytics data is stale',
  INSUFFICIENT_SAMPLE_COUNT: 'Insufficient data — sample count too low',
  COMPUTATION_ERROR:        'Data computation error',
}

const pageStyle = {
  padding: 'var(--token-space-6)',
  maxWidth: '1400px',
  margin: '0 auto',
  fontFamily: 'var(--token-family-base)',
}

const cardShell = {
  background: 'var(--token-surface-card)',
  border: 'var(--token-elevation-border)',
  borderRadius: 'var(--token-radius-card)',
  padding: 'var(--token-space-4)',
  display: 'flex',
  flexDirection: 'column',
  gap: 'var(--token-space-2)',
}

const labelStyle = {
  fontSize: 'var(--token-fs-13)',
  color: 'var(--token-text-secondary)',
  fontWeight: 500,
  textTransform: 'uppercase',
  letterSpacing: '0.05em',
}

const valueStyle = {
  fontSize: 'var(--token-fs-30)',
  fontVariantNumeric: 'var(--token-numeric)',
  color: 'var(--token-text-primary)',
  fontWeight: 700,
  lineHeight: 1.1,
}

const maturityBadgeStyle = {
  display: 'inline-block',
  fontSize: 'var(--token-fs-11)',
  fontWeight: 600,
  letterSpacing: '0.06em',
  color: 'var(--token-warning-emphasis)',
  background: 'var(--token-warning-subtle)',
  border: '1px solid var(--token-warning-default)',
  borderRadius: 'var(--token-radius-pill)',
  padding: 'var(--token-space-1) var(--token-space-2)',
}

const baselinePendingStyle = {
  display: 'inline-block',
  fontSize: 'var(--token-fs-11)',
  fontWeight: 600,
  letterSpacing: '0.06em',
  color: 'var(--token-neutral-600)',
  background: 'var(--token-neutral-100)',
  border: '1px solid var(--token-neutral-300)',
  borderRadius: 'var(--token-radius-pill)',
  padding: 'var(--token-space-1) var(--token-space-2)',
}

const retryBtnStyle = {
  alignSelf: 'flex-start',
  marginTop: 'var(--token-space-2)',
  padding: 'var(--token-space-1) var(--token-space-3)',
  fontSize: 'var(--token-fs-13)',
  fontFamily: 'var(--token-family-base)',
  borderRadius: 'var(--token-radius-control)',
  border: '1px solid var(--token-border-default)',
  background: 'var(--token-surface-card)',
  color: 'var(--token-text-primary)',
  cursor: 'pointer',
}

const drillDownBtnStyle = {
  alignSelf: 'flex-end',
  padding: 'var(--token-space-1) var(--token-space-3)',
  fontSize: 'var(--token-fs-12)',
  fontFamily: 'var(--token-family-base)',
  borderRadius: 'var(--token-radius-control)',
  border: '1px solid var(--token-accent-500)',
  background: 'transparent',
  color: 'var(--token-accent-600)',
  cursor: 'pointer',
  textDecoration: 'none',
}
