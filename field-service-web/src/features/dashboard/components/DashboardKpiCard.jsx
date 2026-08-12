import React from 'react';
import { Link } from 'react-router-dom';
import { KpiCard } from '../../../components/index.js';
import { DataAgeBadge } from './DataAgeBadge.jsx';
import styles from './DashboardKpiCard.module.css';

/** @import { WidgetDto } from '../api/useDashboardWidgets.js' */

/**
 * Builds the drill-down URL for a widget.
 *
 * @param {{ metricKey?: string | null, window?: string | null, segment?: string | null }} opts
 * @returns {string | null}
 */
function drillDownUrl({ metricKey, window: win, segment }) {
  if (!metricKey || !win) return null;
  const params = new URLSearchParams({ metric: metricKey, window: win });
  if (segment && segment !== 'ALL') params.set('segment', segment);
  return `/operations/drill-down?${params}`;
}

const MATURITY_LABELS = {
  PROVISIONAL: 'Provisional',
  NOT_MEANINGFUL: 'Not meaningful',
  BASELINE_PENDING: 'Baseline pending',
};

/**
 * Loading skeleton for one KPI card slot.
 */
function KpiCardSkeleton() {
  return (
    <div className={styles.skeleton} role="status" aria-label="Loading widget">
      <div className={`${styles.skeletonLine} ${styles.skeletonLabel}`} aria-hidden="true" />
      <div className={`${styles.skeletonLine} ${styles.skeletonValue}`} aria-hidden="true" />
      <div className={`${styles.skeletonLine} ${styles.skeletonDelta}`} aria-hidden="true" />
      <span className="sr-only">Loading…</span>
    </div>
  );
}

/**
 * Dashboard KPI card wrapper.
 *
 * Adds four named states (loading / empty / degraded / error) and maturity
 * labelling on top of the Phase 1 KpiCard primitive.
 *
 * @param {{
 *   widget?: WidgetDto | null,
 *   isLoading?: boolean,
 *   isEmpty?: boolean,
 *   error?: Error | null,
 *   onRetry?: () => void,
 *   selectedWindow?: string | null,
 *   selectedSegment?: string | null,
 * }} props
 */
export function DashboardKpiCard({ widget = null, isLoading = false, isEmpty = false, error = null, onRetry, selectedWindow = null, selectedSegment = null }) {
  // ── Loading state ─────────────────────────────────────────────────────────
  if (isLoading && !widget) {
    return <KpiCardSkeleton />;
  }

  // ── Error state ───────────────────────────────────────────────────────────
  if (error && !widget) {
    return (
      <div className={`${styles.stateCard} ${styles.errorCard}`} role="alert">
        <span className={styles.stateIcon} aria-hidden="true">✕</span>
        <p className={styles.stateTitle}>Data unavailable</p>
        <p className={styles.stateDesc}>
          {error.message ?? 'An error occurred loading this metric.'}
        </p>
        {onRetry && (
          <button className={styles.retryBtn} onClick={onRetry} type="button">
            Retry
          </button>
        )}
      </div>
    );
  }

  // ── Empty state ───────────────────────────────────────────────────────────
  if (isEmpty || !widget) {
    return (
      <div className={`${styles.stateCard} ${styles.emptyCard}`} role="status">
        <span className={styles.stateIcon} aria-hidden="true">○</span>
        <p className={styles.stateTitle}>No data</p>
        <p className={styles.stateDesc}>No metric data is available for this period.</p>
      </div>
    );
  }

  const { label, value, delta, deltaLabel, target, current, sparkline, maturity, degraded, dataAge, notMeaningfulReason, metricKey } = widget;
  const drillUrl = drillDownUrl({ metricKey, window: selectedWindow, segment: selectedSegment });

  // ── Not-meaningful state ──────────────────────────────────────────────────
  if (maturity === 'NOT_MEANINGFUL') {
    return (
      <div className={`${styles.stateCard} ${styles.notMeaningfulCard}`} role="status">
        <p className={styles.notMeaningfulLabel}>{label}</p>
        <span className={`${styles.maturityBadge} ${styles.notMeaningful}`}>Not meaningful</span>
        {notMeaningfulReason && (
          <p className={styles.stateDesc}>{notMeaningfulReason}</p>
        )}
      </div>
    );
  }

  // ── Degraded / settled / provisional / baseline-pending ───────────────────
  const maturityLabel = MATURITY_LABELS[maturity] ?? null;

  return (
    <div className={[styles.wrapper, degraded ? styles.degradedWrapper : ''].filter(Boolean).join(' ')}>
      <KpiCard
        label={label}
        value={value}
        delta={delta ?? undefined}
        deltaLabel={deltaLabel ?? undefined}
        target={target ?? undefined}
        current={current ?? undefined}
        sparklineData={sparkline ?? undefined}
      />

      <div className={styles.footer}>
        {maturityLabel && maturity !== 'SETTLED' && (
          <span
            className={`${styles.maturityBadge} ${styles[`maturity-${maturity.toLowerCase().replace('_', '-')}`]}`}
            title={maturity === 'BASELINE_PENDING' ? 'Insufficient history to compute a baseline target.' : undefined}
          >
            {maturityLabel}
          </span>
        )}
        {degraded && (
          <span className={styles.staleBadge} role="status">
            Stale data
          </span>
        )}
        <DataAgeBadge dataAge={dataAge} degraded={degraded} />
        {drillUrl && (
          <Link
            to={drillUrl}
            className={styles.drillLink}
            aria-label={`View work orders for ${label}`}
          >
            View details
          </Link>
        )}
      </div>
    </div>
  );
}
