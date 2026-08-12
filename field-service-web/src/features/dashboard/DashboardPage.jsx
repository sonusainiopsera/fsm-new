import React, { useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';

import { PageHeader } from '../../components/index.js';
import { useDashboardWidgets } from './api/useDashboardWidgets.js';
import { DashboardKpiCard } from './components/DashboardKpiCard.jsx';
import { DegradedNotice }   from './components/DegradedNotice.jsx';
import { MetricChart }      from './components/MetricChart.jsx';
import { SegmentFilter }    from './components/SegmentFilter.jsx';
import { WidgetGrid }       from './components/WidgetGrid.jsx';
import { WindowSelector }   from './components/WindowSelector.jsx';
import styles               from './DashboardPage.module.css';

const VALID_WINDOWS = new Set(['7d', '30d', '90d']);

/**
 * Operations manager dashboard.
 *
 * URL params:
 *   ?window=30d   — selected time window (7d | 30d | 90d), default 30d
 *   ?priority=    — optional priority filter
 *   ?team=        — optional team filter
 *
 * @returns {JSX.Element}
 */
export default function DashboardPage() {
  const [params, setParams] = useSearchParams();

  const windowParam   = VALID_WINDOWS.has(params.get('window') ?? '') ? params.get('window') : '30d';
  const priorityParam = params.get('priority') ?? '';
  const teamParam     = params.get('team')     ?? '';

  const { data, isPending, isError, error, isFetching, refetch } = useDashboardWidgets({
    window:   windowParam,
    priority: priorityParam,
    team:     teamParam,
  });

  const handleWindowChange = useCallback((w) => {
    setParams((prev) => {
      const next = new URLSearchParams(prev);
      next.set('window', w);
      return next;
    });
  }, [setParams]);

  const handlePriorityChange = useCallback((p) => {
    setParams((prev) => {
      const next = new URLSearchParams(prev);
      if (p) next.set('priority', p); else next.delete('priority');
      return next;
    });
  }, [setParams]);

  const handleTeamChange = useCallback((t) => {
    setParams((prev) => {
      const next = new URLSearchParams(prev);
      if (t) next.set('team', t); else next.delete('team');
      return next;
    });
  }, [setParams]);

  const widgets = data?.widgets ?? [];
  const trend   = data?.trend   ?? null;
  const meta    = data?.meta    ?? null;

  const isFirstLoad = isPending && !data;
  const isAllDegraded = meta?.allDegraded ?? false;

  return (
    <main className={styles.page}>
      <PageHeader
        title="Operations Dashboard"
        subtitle={
          isFetching && !isPending
            ? 'Updating…'
            : meta?.generatedAt
            ? `Updated ${new Date(meta.generatedAt).toLocaleTimeString()}`
            : undefined
        }
      />

      {/* Controls toolbar */}
      <div className={styles.controls}>
        <WindowSelector value={windowParam} onChange={handleWindowChange} />
        <SegmentFilter
          priority={priorityParam}
          team={teamParam}
          onPriorityChange={handlePriorityChange}
          onTeamChange={handleTeamChange}
        />
      </div>

      {/* Dashboard-level degraded notice */}
      {isAllDegraded && !isFirstLoad && (
        <DegradedNotice generatedAt={meta?.generatedAt} />
      )}

      {/* Full-page error — no cached data */}
      {isError && !data && (
        <div className={styles.pageError} role="alert">
          <p className={styles.pageErrorTitle}>Dashboard unavailable</p>
          <p className={styles.pageErrorDesc}>
            {error?.message ?? 'Unable to load dashboard data. Please try again.'}
          </p>
          <button className={styles.retryBtn} type="button" onClick={() => refetch()}>
            Retry
          </button>
        </div>
      )}

      {/* Widget grid */}
      {(isFirstLoad || widgets.length > 0) && (
        <WidgetGrid>
          {isFirstLoad
            ? Array.from({ length: 7 }, (_, i) => (
                <DashboardKpiCard key={i} isLoading={true} />
              ))
            : widgets.map((w) => (
                <DashboardKpiCard
                  key={w.id}
                  widget={w}
                  isLoading={false}
                  isEmpty={false}
                  error={null}
                  onRetry={() => refetch()}
                />
              ))}
        </WidgetGrid>
      )}

      {/* Empty state — loaded but no widgets */}
      {!isFirstLoad && !isError && widgets.length === 0 && (
        <div className={styles.empty} role="status">
          <p className={styles.emptyTitle}>No metrics available</p>
          <p className={styles.emptyDesc}>
            No KPI data is available for the selected window and filters.
          </p>
        </div>
      )}

      {/* Trend chart */}
      {trend && !isFirstLoad && (
        <section className={styles.chartSection} aria-label="Trend chart">
          <h2 id="trend-heading" className={styles.chartHeading}>{trend.caption}</h2>
          <MetricChart trend={trend} headingId="trend-heading" />
        </section>
      )}
    </main>
  );
}
