/**
 * @fileoverview ReconciliationBanner — explains the relationship between a widget's
 * KPI aggregate value and the drill-down work order count (WO-168).
 *
 * Four cases:
 *   MATCHED                — counts align; banner is visually quiet (success tone)
 *   DIVERGED / READ_MODEL_STALE  — read model is older than freshness budget
 *   DIVERGED / PROVISIONAL_COHORT — first-time fix cohort not yet fully matured
 *   DIVERGED / SCOPE_RESTRICTED   — manager's scope covers a subset of the aggregated population
 */

/**
 * @param {{
 *   widgetValue: number | null,
 *   widgetDataAsOf: string | null,
 *   resultCount: number,
 *   status: 'MATCHED' | 'DIVERGED',
 *   reason: 'READ_MODEL_STALE' | 'PROVISIONAL_COHORT' | 'SCOPE_RESTRICTED' | null,
 *   metricLabel: string
 * }} props
 */
export function ReconciliationBanner({
  widgetValue,
  widgetDataAsOf,
  resultCount,
  status,
  reason,
  metricLabel,
}) {
  if (status === 'MATCHED') {
    return (
      <div
        role="status"
        aria-live="polite"
        data-testid="reconciliation-banner"
        data-reconciliation-status="MATCHED"
        style={bannerStyle('matched')}
      >
        <span aria-hidden="true" style={{ fontSize: 'var(--token-fs-14)' }}>✓</span>
        <span>
          {resultCount.toLocaleString()} work order{resultCount !== 1 ? 's' : ''} found,
          matching the {metricLabel} widget value
          {widgetValue != null ? ` of ${formatWidgetValue(widgetValue)}` : ''}.
        </span>
      </div>
    )
  }

  // DIVERGED — reason explains the mismatch
  const { title, detail } = divergedMessage(reason, widgetValue, widgetDataAsOf, metricLabel)

  return (
    <div
      role="status"
      aria-live="polite"
      data-testid="reconciliation-banner"
      data-reconciliation-status="DIVERGED"
      data-reconciliation-reason={reason}
      style={bannerStyle('diverged')}
    >
      <span aria-hidden="true" style={{ fontSize: 'var(--token-fs-14)' }}>ℹ</span>
      <div>
        <strong style={{ fontSize: 'var(--token-fs-14)' }}>{title}</strong>
        <p style={{ margin: 'var(--token-space-1) 0 0', fontSize: 'var(--token-fs-13)', color: 'var(--token-text-secondary)' }}>
          {detail}
        </p>
      </div>
    </div>
  )
}

// ── helpers ───────────────────────────────────────────────────────────────────

function formatWidgetValue(v) {
  if (typeof v !== 'number') return String(v)
  if (Number.isInteger(v)) return v.toLocaleString()
  return v.toFixed(2)
}

function formatDataAge(isoString) {
  if (!isoString) return null
  try {
    const d = new Date(isoString)
    return d.toLocaleString(undefined, {
      month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
    })
  } catch {
    return isoString
  }
}

function divergedMessage(reason, widgetValue, widgetDataAsOf, metricLabel) {
  const valueStr = widgetValue != null ? ` (widget shows ${formatWidgetValue(widgetValue)})` : ''
  const ageStr = widgetDataAsOf ? ` as of ${formatDataAge(widgetDataAsOf)}` : ''

  switch (reason) {
    case 'READ_MODEL_STALE':
      return {
        title: 'Count may differ — analytics data is refreshing',
        detail: `The ${metricLabel} widget value${valueStr} was computed${ageStr} and may not yet ` +
          'reflect the most recent work orders. Refresh the page in a few minutes for an ' +
          'up-to-date comparison.',
      }
    case 'PROVISIONAL_COHORT':
      return {
        title: 'Count may differ — cohort is still maturing',
        detail: `The ${metricLabel} metric includes work orders whose outcomes are not yet ` +
          'confirmed (for example, first-time fix status depends on follow-up visits within ' +
          'the maturation window). The list shows all eligible work orders; the widget ' +
          `value${valueStr} may change as the cohort matures.`,
      }
    case 'SCOPE_RESTRICTED':
      return {
        title: 'Count may differ — your scope covers a subset',
        detail: `The ${metricLabel} widget value${valueStr} was aggregated across a wider ` +
          'population than your access permits. This list shows only the work orders within ' +
          'your authorised scope.',
      }
    default:
      return {
        title: 'Count may differ from the widget value',
        detail: `The ${metricLabel} widget${valueStr} and this list were generated at different ` +
          `times${ageStr}. Counts may diverge due to recent changes or access scope differences.`,
      }
  }
}

function bannerStyle(variant) {
  const isMatched = variant === 'matched'
  return {
    display: 'flex',
    alignItems: 'flex-start',
    gap: 'var(--token-space-2)',
    padding: 'var(--token-space-3) var(--token-space-4)',
    borderRadius: 'var(--token-radius-card)',
    background: isMatched ? 'var(--token-success-subtle)' : 'var(--token-info-subtle, var(--token-neutral-50))',
    border: `1px solid ${isMatched ? 'var(--token-success-default)' : 'var(--token-info-default, var(--token-neutral-200))'}`,
    color: isMatched ? 'var(--token-success-emphasis)' : 'var(--token-text-primary)',
    fontSize: 'var(--token-fs-13)',
    marginBottom: 'var(--token-space-4)',
  }
}
