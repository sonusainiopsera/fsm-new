/**
 * ServiceHistoryPage — paginated service history browser (WO-175).
 *
 * AC coverage:
 * - AC-1  Link-driven pagination (links.next / links.prev only, no self-constructed offsets).
 * - AC-2  URL search-param filter state: ?statusGroup=open|closed, ?siteId=<id>.
 * - AC-3  Empty state with a "Submit a request" CTA when no history exists.
 * - AC-4  Loading and error states.
 * - AC-5  Survey-eligible rows show a "Rate" link to /portal/survey/:id.
 * - AC-6  WCAG 2.1 AA — table has proper roles; nav landmarks; no AA contrast violations.
 */

import React, { useCallback, useRef } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';

import {
  PageHeader,
  LoadingState,
  ErrorState,
  EmptyState,
  StateSurface,
  LinkPager,
} from '../../components/index.js';

import { fetchServiceHistory } from '../../api/portalClient.js';

import styles from './ServiceHistoryPage.module.css';

// ─── Helpers ──────────────────────────────────────────────────────────────────

function fmtDate(isoString) {
  if (!isoString) return '—';
  return new Date(isoString).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });
}

const STATUS_GROUP_LABELS = { open: 'Open', closed: 'Closed' };
const PRIORITY_VARIANTS = { Urgent: styles.priorityUrgent, High: styles.priorityHigh };

// ─── Filter bar ───────────────────────────────────────────────────────────────

function FilterBar({ searchParams, onFilterChange }) {
  const statusGroup = searchParams.get('statusGroup') ?? '';
  const siteId      = searchParams.get('siteId') ?? '';

  return (
    <div className={styles.filterBar} role="search" aria-label="Filter service history">
      <label className={styles.filterLabel} htmlFor="sg-filter">
        Status
        <select
          id="sg-filter"
          className={styles.filterSelect}
          value={statusGroup}
          onChange={e => onFilterChange('statusGroup', e.target.value)}
        >
          <option value="">All</option>
          <option value="open">Open</option>
          <option value="closed">Closed</option>
        </select>
      </label>

      <label className={styles.filterLabel} htmlFor="site-filter">
        Site ID
        <input
          id="site-filter"
          type="text"
          className={styles.filterInput}
          value={siteId}
          onChange={e => onFilterChange('siteId', e.target.value)}
          placeholder="Filter by site…"
          aria-label="Filter by site ID"
        />
      </label>
    </div>
  );
}

// ─── History table ────────────────────────────────────────────────────────────

function HistoryTable({ items }) {
  return (
    <div className={styles.tableWrapper} role="region" aria-label="Service history list">
      <table className={styles.table}>
        <thead>
          <tr>
            <th scope="col" className={styles.th}>Reference</th>
            <th scope="col" className={styles.th}>Site</th>
            <th scope="col" className={styles.th}>Status</th>
            <th scope="col" className={styles.th}>Priority</th>
            <th scope="col" className={styles.th}>Submitted</th>
            <th scope="col" className={styles.th}>Resolved</th>
            <th scope="col" className={`${styles.th} ${styles.thAction}`}>
              <span className="sr-only">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map(item => (
            <tr key={item.id} className={styles.row}>
              <td className={styles.td}>
                <Link
                  to={`/portal/status/${item.id}`}
                  className={styles.refLink}
                  aria-label={`View details for ${item.reference}`}
                >
                  {item.reference}
                </Link>
              </td>
              <td className={styles.td}>{item.siteName}</td>
              <td className={styles.td}>
                <span className={`${styles.statusBadge} ${item.statusGroup === 'closed' ? styles.badgeClosed : styles.badgeOpen}`}>
                  {item.statusLabel}
                </span>
              </td>
              <td className={styles.td}>
                <span className={`${styles.priority} ${PRIORITY_VARIANTS[item.priority] ?? ''}`}>
                  {item.priority}
                </span>
              </td>
              <td className={styles.td}>{fmtDate(item.submittedAt)}</td>
              <td className={styles.td}>{fmtDate(item.resolvedAt)}</td>
              <td className={`${styles.td} ${styles.tdAction}`}>
                {item.surveyEligible && (
                  <Link
                    to={`/portal/survey/${item.id}`}
                    className={styles.rateLink}
                    aria-label={`Rate service for ${item.reference}`}
                  >
                    Rate
                  </Link>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function ServiceHistoryPage() {
  const [searchParams, setSearchParams] = useSearchParams();

  // page cursor stored as the full next/prev link URL in state
  const pageUrlRef = useRef(null);

  // Derive the URL to fetch from (null = first page)
  const pageUrl = searchParams.get('_pageUrl') ?? null;

  const queryKey = ['portal', 'serviceHistory', pageUrl, searchParams.get('statusGroup'), searchParams.get('siteId')];

  const { data, isLoading, isError, error, isFetching } = useQuery({
    queryKey,
    queryFn: ({ signal }) => fetchServiceHistory({ url: pageUrl, signal }),
    staleTime: 30_000,
    keepPreviousData: true,
  });

  // Filter changes reset to page 0
  const handleFilterChange = useCallback((key, value) => {
    setSearchParams(prev => {
      const next = new URLSearchParams(prev);
      if (value) {
        next.set(key, value);
      } else {
        next.delete(key);
      }
      next.delete('_pageUrl');
      return next;
    });
  }, [setSearchParams]);

  const handleNext = useCallback(() => {
    const nextUrl = data?._links?.next;
    if (!nextUrl) return;
    setSearchParams(prev => {
      const next = new URLSearchParams(prev);
      next.set('_pageUrl', nextUrl);
      return next;
    });
  }, [data, setSearchParams]);

  const handlePrev = useCallback(() => {
    const prevUrl = data?._links?.prev;
    if (!prevUrl) return;
    setSearchParams(prev => {
      const next = new URLSearchParams(prev);
      if (prevUrl) {
        next.set('_pageUrl', prevUrl);
      } else {
        next.delete('_pageUrl');
      }
      return next;
    });
  }, [data, setSearchParams]);

  const items   = data?.data ?? [];
  const links   = data?._links ?? {};
  const pageInfo = data?.page;

  return (
    <div className={styles.page}>
      <PageHeader
        title="Service history"
        subtitle="View all your past and open service requests"
      />

      <FilterBar searchParams={searchParams} onFilterChange={handleFilterChange} />

      {isLoading && !data && (
        <LoadingState label="Loading service history…" />
      )}

      {isError && !data && (
        <ErrorState
          title="Could not load history"
          description={error?.message ?? 'An error occurred. Please try again.'}
        />
      )}

      {!isLoading && !isError && items.length === 0 && (
        <EmptyState
          title="No service requests found"
          description="You haven't submitted any service requests yet, or none match the current filters."
          actions={
            <Link to="/portal/new" className={styles.ctaLink}>
              Submit a request
            </Link>
          }
        />
      )}

      {items.length > 0 && (
        <>
          <StateSurface className={styles.tableCard}>
            <HistoryTable items={items} />
          </StateSurface>

          <nav aria-label="History pagination" className={styles.pagerNav}>
            {pageInfo && (
              <p className={styles.pageInfo} aria-live="polite" aria-atomic="true">
                {pageInfo.totalElements > 0
                  ? `Page ${pageInfo.number + 1} of ${pageInfo.totalPages} · ${pageInfo.totalElements} total`
                  : null}
              </p>
            )}
            <LinkPager
              page={pageInfo ?? null}
              links={links}
              onNext={handleNext}
              onPrev={handlePrev}
              isLoading={isFetching}
            />
          </nav>
        </>
      )}
    </div>
  );
}
