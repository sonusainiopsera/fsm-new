import React from 'react';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';

import { StateSurface } from '../../components/StateSurface/StateSurface.jsx';
import { apiFetch } from '../../api/http.js';
import { useRecommendations } from './api/useRecommendations.js';
import { RecommendationCard } from './components/RecommendationCard.jsx';
import styles from './DispatchRecommendationsPage.module.css';

/**
 * Main dispatcher recommendations page.
 *
 * Displays a ranked shortlist of technician candidates for an unassigned work
 * order, with expandable per-factor breakdowns and explicit states for every
 * outcome (loading, empty, degraded, error, forbidden, not-assignable).
 */
export default function DispatchRecommendationsPage() {
  const { workOrderId } = useParams();

  const {
    candidates,
    meta,
    hasNext,
    isLoading,
    isFetching,
    isError,
    error,
    loadMore,
    refresh,
  } = useRecommendations(workOrderId);

  // ── Error states ────────────────────────────────────────────────────────────

  if (isLoading) {
    return <StateSurface variant="loading" />;
  }

  if (error?.status === 403) {
    return <StateSurface variant="permission-denied" />;
  }

  if (error?.status === 422) {
    return (
      <StateSurface
        variant="error"
        title="Work order not assignable"
        description={error?.message ?? 'This work order cannot be assigned in its current state.'}
      />
    );
  }

  if (error?.status === 503) {
    return (
      <StateSurface
        variant="degraded"
        description="Eligibility data is temporarily unavailable. Please try again."
        onRetry={refresh}
        retryLabel="Retry"
      />
    );
  }

  if (isError) {
    return (
      <StateSurface
        variant="error"
        onRetry={refresh}
        retryLabel="Retry"
      />
    );
  }

  // ── Zero-candidate state ────────────────────────────────────────────────────

  if (candidates.length === 0) {
    return (
      <div className={styles.page}>
        <PageHeader onRefresh={refresh} />
        <WorkOrderContextBanner workOrderId={workOrderId} />
        <StateSurface
          variant="empty"
          title="No eligible candidates"
          description={buildExclusionText(meta?.exclusionSummary)}
        />
      </div>
    );
  }

  // ── Loaded state ────────────────────────────────────────────────────────────

  const aggregateDegraded = meta?.travelEstimateDegraded || meta?.partsDataDegraded;

  return (
    <div className={styles.page}>
      <PageHeader onRefresh={refresh} />
      <WorkOrderContextBanner workOrderId={workOrderId} />

      {aggregateDegraded && (
        <div
          className={styles.degradedBanner}
          role="status"
          aria-live="polite"
        >
          <span aria-hidden="true">⚠</span>{' '}
          Some data quality is reduced.
          {meta?.travelEstimateDegraded && ' Travel times are estimates only.'}
          {meta?.partsDataDegraded && ' Parts availability data is unavailable.'}
        </div>
      )}

      {meta && (
        <div className={styles.poolMeta}>
          <span>{meta.candidatePoolSize ?? '—'} candidates evaluated</span>
          {meta.exclusionSummary?.length > 0 && (
            <span className={styles.excluded}>
              ·{' '}
              {meta.exclusionSummary.reduce((n, e) => n + e.count, 0)} excluded
            </span>
          )}
        </div>
      )}

      {isFetching && <StateSurface variant="loading" isRefetch />}

      <ul className={styles.list} aria-label="Ranked technician candidates">
        {candidates.map(candidate => (
          <RecommendationCard key={candidate.technicianId} candidate={candidate} />
        ))}
      </ul>

      {hasNext && (
        <div className={styles.loadMoreRow}>
          <button
            className={styles.loadMoreBtn}
            onClick={loadMore}
            disabled={isFetching}
            aria-busy={isFetching}
          >
            {isFetching ? 'Loading…' : 'Load more'}
          </button>
        </div>
      )}

      {meta?.snapshotId && (
        <p className={styles.snapshotNote}>
          Snapshot {meta.snapshotId.slice(0, 8)}…
          {meta.generatedAt
            ? ` · generated ${new Date(meta.generatedAt).toLocaleTimeString()}`
            : ''}
        </p>
      )}
    </div>
  );
}

/**
 * @param {{ onRefresh: () => void }} props
 */
function PageHeader({ onRefresh }) {
  return (
    <header className={styles.pageHeader}>
      <h1 className={styles.heading}>Technician recommendations</h1>
      <button
        className={styles.refreshBtn}
        onClick={onRefresh}
        aria-label="Refresh recommendations"
      >
        Refresh
      </button>
    </header>
  );
}

/**
 * Fetches and displays minimal work order context (customer, site, priority,
 * response and resolution deadlines). Renders nothing if the fetch fails.
 *
 * @param {{ workOrderId: string }} props
 */
function WorkOrderContextBanner({ workOrderId }) {
  const { data } = useQuery({
    queryKey: ['work-order-context', workOrderId],
    queryFn: ({ signal }) => apiFetch(`/work-orders/${workOrderId}`, { signal }),
    enabled: Boolean(workOrderId),
    staleTime: 5 * 60 * 1000,
    refetchOnWindowFocus: false,
    retry: false,
  });

  if (!data) return null;

  return (
    <dl className={styles.contextBanner} aria-label="Work order context">
      <div className={styles.contextItem}>
        <dt className={styles.contextLabel}>Reference</dt>
        <dd className={styles.contextValue}>{data.reference ?? '—'}</dd>
      </div>
      {data.customerName && (
        <div className={styles.contextItem}>
          <dt className={styles.contextLabel}>Customer</dt>
          <dd className={styles.contextValue}>{data.customerName}</dd>
        </div>
      )}
      {data.siteName && (
        <div className={styles.contextItem}>
          <dt className={styles.contextLabel}>Site</dt>
          <dd className={styles.contextValue}>{data.siteName}</dd>
        </div>
      )}
      {data.priority && (
        <div className={styles.contextItem}>
          <dt className={styles.contextLabel}>Priority</dt>
          <dd className={styles.contextValue}>{data.priority}</dd>
        </div>
      )}
      {data.responseDeadline && (
        <div className={styles.contextItem}>
          <dt className={styles.contextLabel}>Response by</dt>
          <dd className={styles.contextValue}>
            {new Date(data.responseDeadline).toLocaleString()}
          </dd>
        </div>
      )}
      {data.resolutionDeadline && (
        <div className={styles.contextItem}>
          <dt className={styles.contextLabel}>Resolve by</dt>
          <dd className={styles.contextValue}>
            {new Date(data.resolutionDeadline).toLocaleString()}
          </dd>
        </div>
      )}
    </dl>
  );
}

/**
 * @param {Array<{ reason: string, count: number }> | null | undefined} summary
 * @returns {string}
 */
function buildExclusionText(summary) {
  if (!summary?.length) {
    return 'There are no eligible technicians for this work order.';
  }
  const parts = summary.map(
    e => `${e.count} ${e.reason.replace(/_/g, ' ').toLowerCase()}`,
  );
  return `No eligible technicians. Excluded: ${parts.join(', ')}.`;
}
