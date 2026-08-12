/**
 * ServiceRequestStatusPage — live status tracking for a portal service request.
 *
 * AC coverage (WO-174):
 * - AC-4  60-second conditional-GET polling via usePortalQuery.
 * - AC-4  Strong ETag / 304 support; no re-render on 304 (structural sharing).
 * - AC-5  FreshnessBanner shows degraded/not-connected states.
 * - AC-6  StatusTimeline renders API-provided labels only — no internal codes.
 * - AC-7  Appearance inherited from AppearanceProvider.
 * - AC-8  No PII, no internal state codes, no GPS data in DOM.
 */

import React from 'react';
import { useParams, useLocation, Link } from 'react-router-dom';

import {
  PageHeader,
  LoadingState,
  ErrorState,
  StateSurface,
} from '../../components/index.js';

import { FreshnessBanner } from '../../components/freshness/FreshnessBanner.jsx';
import { StatusTimeline } from '../../components/status/StatusTimeline.jsx';
import { usePortalQuery } from '../../api/useConditionalQuery.js';
import { fetchServiceRequestStatus } from '../../api/portalClient.js';

import styles from './ServiceRequestStatusPage.module.css';

const POLL_INTERVAL_MS = 60_000;

/** Format an ISO date string as a readable date+time. */
function fmt(isoString) {
  if (!isoString) return null;
  return new Date(isoString).toLocaleString(undefined, {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export default function ServiceRequestStatusPage() {
  const { requestId } = useParams();
  const location = useLocation();

  // Navigation state passed from NewServiceRequestPage on success
  const navState = location.state ?? {};

  const {
    data,
    isLoading,
    isError,
    error,
    isFetching,
    refetch,
    dataUpdatedAt,
  } = usePortalQuery({
    queryKey: ['portal', 'status', requestId],
    queryFn: ({ signal, ifNoneMatch }) =>
      fetchServiceRequestStatus(requestId, { signal, ifNoneMatch }),
    enabled: Boolean(requestId),
    staleBeyondMs: POLL_INTERVAL_MS,
  });

  const observedAt = dataUpdatedAt ? new Date(dataUpdatedAt).toISOString() : null;

  // ── 404 — request not found or access denied ────────────────────────────────
  if (isError && (error?.status === 404 || error?.status === 403)) {
    return (
      <div className={styles.page}>
        <PageHeader title="Service request" />
        <ErrorState
          title="Request not found"
          description="We couldn't find this service request. It may have been removed or you may not have permission to view it."
          actions={<Link to="/portal/new" className={styles.link}>Submit a new request</Link>}
        />
      </div>
    );
  }

  // ── 429 — rate limited ─────────────────────────────────────────────────────
  if (isError && error?.status === 429) {
    return (
      <div className={styles.page}>
        <PageHeader title="Service request" />
        <ErrorState
          title="Too many requests"
          description="You're checking too frequently. Please wait a moment before refreshing."
          actions={
            <button type="button" className={styles.link} onClick={() => refetch()}>
              Try again
            </button>
          }
        />
      </div>
    );
  }

  // ── Initial loading ─────────────────────────────────────────────────────────
  if (isLoading && !data) {
    return (
      <div className={styles.page}>
        <PageHeader title="Service request" />
        <LoadingState label="Loading your service request…" />
      </div>
    );
  }

  // ── Degraded: polling failed at least once but we have stale data ───────────
  const isDegraded = isError && Boolean(data);

  return (
    <div className={styles.page}>
      <PageHeader
        title={data?.reference ? `Request ${data.reference}` : 'Service request'}
        subtitle="Track the progress of your request"
      />

      <FreshnessBanner
        observedAt={observedAt}
        staleAfterSeconds={POLL_INTERVAL_MS / 1000}
        degraded={isDegraded}
        isError={isError && !data}
        onRetry={() => refetch()}
        className={styles.banner}
      />

      {data && (
        <>
          {/* Summary card */}
          <StateSurface className={styles.summaryCard}>
            <div className={styles.summaryGrid}>
              <div className={styles.summaryItem}>
                <span className={styles.summaryLabel}>Reference</span>
                <span className={styles.summaryValue}>{data.reference}</span>
              </div>

              {data.currentStatusLabel && (
                <div className={styles.summaryItem}>
                  <span className={styles.summaryLabel}>Status</span>
                  <span
                    className={styles.summaryValue}
                    aria-live="polite"
                    aria-atomic="true"
                  >
                    {data.currentStatusLabel}
                  </span>
                </div>
              )}

              {data.respondByAt && (
                <div className={styles.summaryItem}>
                  <span className={styles.summaryLabel}>Response by</span>
                  <span className={styles.summaryValue}>{fmt(data.respondByAt)}</span>
                </div>
              )}

              {data.resolveByAt && (
                <div className={styles.summaryItem}>
                  <span className={styles.summaryLabel}>Resolution by</span>
                  <span className={styles.summaryValue}>{fmt(data.resolveByAt)}</span>
                </div>
              )}

              {data.siteName && (
                <div className={styles.summaryItem}>
                  <span className={styles.summaryLabel}>Site</span>
                  <span className={styles.summaryValue}>{data.siteName}</span>
                </div>
              )}
            </div>
          </StateSurface>

          {/* Timeline */}
          {(data.milestones?.length > 0 || data.currentStatusLabel) && (
            <StatusTimeline
              milestones={data.milestones ?? []}
              currentStatusLabel={data.currentStatusLabel}
              className={styles.timeline}
            />
          )}

          {/* Next steps or resolution note */}
          {data.customerNote && (
            <StateSurface className={styles.noteCard}>
              <h3 className={styles.noteHeading}>Next steps</h3>
              <p className={styles.noteBody}>{data.customerNote}</p>
            </StateSurface>
          )}
        </>
      )}

      <div className={styles.footer}>
        <Link to="/portal/new" className={styles.link}>
          Submit another request
        </Link>
      </div>
    </div>
  );
}
