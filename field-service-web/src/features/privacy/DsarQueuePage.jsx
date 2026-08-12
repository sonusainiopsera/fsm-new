/**
 * DsarQueuePage — DSAR request list with 30-day countdown and at-risk flagging.
 *
 * Countdown is derived from the server-supplied dueAt and atRisk flag, not
 * recomputed client-side, so the client cannot disagree with the compliance metric.
 * Overdue requests (remainingDays <= 0) receive a distinct overdue treatment.
 *
 * @module features/privacy/DsarQueuePage
 */

import React, { useState } from 'react';

import {
  PageHeader, DataTable, Button,
  EmptyState, LoadingState, ErrorState, PermissionDeniedState,
} from '../../components/index.js';
import { usePagedQuery } from '../../shared/hooks/usePagedQuery.js';
import { listDsarRequests } from '../../api/privacyAdmin.js';

import { PaginationBar } from '../admin/PaginationBar.jsx';
import styles from '../admin/admin.module.css';

const DSAR_STATES = ['', 'RECEIVED', 'IDENTITY_PENDING', 'VERIFIED', 'IN_PROGRESS', 'FULFILLED', 'REJECTED', 'WITHDRAWN'];

const TYPE_LABEL = {
  ACCESS: 'Access',
  PORTABILITY: 'Portability',
  RECTIFICATION: 'Rectification',
  ERASURE: 'Erasure',
};

/**
 * @param {number} remainingDays
 * @param {boolean} atRisk
 * @returns {React.ReactNode}
 */
function CountdownCell(remainingDays, atRisk) {
  if (remainingDays < 0) {
    return (
      <span className={styles.countdownOverdue} aria-label={`Overdue by ${Math.abs(remainingDays)} day(s)`}>
        Overdue
      </span>
    );
  }
  if (atRisk) {
    return (
      <span className={styles.countdownAtRisk} aria-label={`${remainingDays} day(s) remaining — at risk`}>
        <span className={styles.tabularFigure}>{remainingDays}</span> d
        <span className={styles.atRiskFlag} aria-hidden="true"> ⚠</span>
      </span>
    );
  }
  return (
    <span className={styles.tabularFigure} aria-label={`${remainingDays} day(s) remaining`}>
      {remainingDays} d
    </span>
  );
}

const COLUMNS = [
  { key: 'requestType', header: 'Type',    sortable: true,
    render: (v) => TYPE_LABEL[v] ?? v },
  { key: 'subjectType', header: 'Subject type', sortable: true },
  { key: 'state',       header: 'State',   sortable: true },
  { key: 'submittedAt', header: 'Submitted', sortable: true,
    render: (v) => new Date(v).toLocaleDateString() },
  { key: 'remainingDays', header: 'Remaining', sortable: false,
    render: (v, row) => CountdownCell(v, row.atRisk) },
];

/**
 * @param {{ roles?: string[], onSelect?: (row: object) => void }} props
 */
export function DsarQueuePage({ roles = [], onSelect }) {
  const [stateFilter, setStateFilter] = useState('');

  const {
    data, page: pageMeta, isLoading, isError, error, state,
    setPage, setSort, setFilter, refetch,
  } = usePagedQuery({
    queryKey: ['privacy', 'dsar-requests', stateFilter],
    queryFn: (s) => listDsarRequests({
      page: s.page,
      size: s.pageSize,
      sort: s.sort ? `${s.sort.field},${s.sort.direction}` : 'dueAt,asc',
      state: stateFilter || undefined,
    }),
    defaultPageSize: 20,
    allowedSorts: new Set(['requestType', 'subjectType', 'state', 'submittedAt', 'dueAt']),
  });

  if (isLoading) return <LoadingState />;
  if (isError) {
    const status = error?.status ?? error?.statusCode;
    if (status === 403) return <PermissionDeniedState />;
    return <ErrorState onRetry={refetch} description="Could not load DSAR requests." />;
  }

  const rows = data ?? [];

  return (
    <div className={styles.page}>
      <PageHeader title="DSAR Queue" description="Data subject access requests and 30-day fulfilment countdown." />

      <div className={styles.filterBar}>
        <label htmlFor="dsar-state-filter" className={styles.filterLabel}>Filter by state</label>
        <select
          id="dsar-state-filter"
          value={stateFilter}
          onChange={(e) => { setStateFilter(e.target.value); setPage(0); }}
          className={styles.filterSelect}
        >
          {DSAR_STATES.map((s) => (
            <option key={s} value={s}>{s || 'All states'}</option>
          ))}
        </select>
      </div>

      <DataTable
        caption="DSAR request queue"
        columns={COLUMNS}
        rows={rows}
        onSort={(field, direction) => setSort({ field, direction })}
        currentSort={state.sort}
        rowActions={onSelect ? (row) => (
          <Button variant="ghost" onClick={() => onSelect(row)}>View</Button>
        ) : undefined}
        emptySlot={<EmptyState description="No DSAR requests match the current filter." />}
      />

      <PaginationBar
        page={pageMeta?.number ?? 0}
        totalPages={pageMeta?.totalPages ?? 1}
        totalElements={pageMeta?.totalElements ?? 0}
        onPage={setPage}
      />
    </div>
  );
}
