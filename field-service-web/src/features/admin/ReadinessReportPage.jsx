/**
 * ReadinessReportPage — certification data-readiness overview for MANAGER/ADMIN.
 *
 * Shows:
 * - KPI row: readiness %, gate target (100%), gate status, blocking technician count
 * - Trend chart: readiness % over recent weekly snapshots (Recharts)
 * - DataTable: blocking technicians with gap detail
 * - CSV download button for the full gap export
 *
 * @module features/admin/ReadinessReportPage
 */

import React, { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts';

import {
  PageHeader, DataTable, Button, EmptyState, LoadingState, ErrorState,
} from '../../components/index.js';
import {
  getReadiness, getGaps, listSnapshots, triggerSnapshot, gapsCsvUrl,
} from '../../api/readiness.js';

import { PaginationBar } from './PaginationBar.jsx';
import styles from './admin.module.css';

const GAP_COLUMNS = [
  { key: 'employeeCode',   header: 'Employee code', sortable: false, render: (v) => v ?? '—' },
  { key: 'displayName',    header: 'Name',          sortable: false, render: (v) => v ?? '—' },
  {
    key: 'missingFields',
    header: 'Missing fields',
    sortable: false,
    render: (v) => v?.length > 0 ? v.join(', ') : '—',
  },
  {
    key: 'missingCertificationTypes',
    header: 'Missing certs',
    sortable: false,
    render: (v) => v?.length > 0 ? v.join(', ') : '—',
  },
  {
    key: 'expiredCertificationTypes',
    header: 'Expired certs',
    sortable: false,
    render: (v) => v?.length > 0 ? v.join(', ') : '—',
  },
  {
    key: 'expiringSoonCertificationTypes',
    header: 'Expiring soon',
    sortable: false,
    render: (v) => v?.length > 0 ? v.join(', ') : '—',
  },
];

/**
 * @param {{ roles?: string[] }} props
 */
export function ReadinessReportPage({ roles = [] }) {
  const isAdmin = roles.some((r) => r === 'ADMIN');
  const [gapPage, setGapPage] = useState(0);
  const queryClient = useQueryClient();

  const { data: aggregate, isLoading: aggLoading, isError: aggError } = useQuery({
    queryKey: ['readiness-aggregate'],
    queryFn: () => getReadiness(),
  });

  const { data: gapsData, isLoading: gapsLoading } = useQuery({
    queryKey: ['readiness-gaps', gapPage],
    queryFn: () => getGaps({ page: gapPage, size: 20 }),
  });

  const { data: snapshotsData } = useQuery({
    queryKey: ['readiness-snapshots'],
    queryFn: () => listSnapshots({ size: 10 }),
  });

  const snapshotMutation = useMutation({
    mutationFn: () => triggerSnapshot(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['readiness-snapshots'] });
      queryClient.invalidateQueries({ queryKey: ['readiness-aggregate'] });
    },
  });

  if (aggLoading) return <LoadingState />;
  if (aggError)   return <ErrorState message="Failed to load readiness report" />;

  const chartData = (snapshotsData?.data ?? [])
    .slice()
    .reverse()
    .map((s) => ({ week: s.isoWeek, readiness: s.readinessPercent }));

  const gaps       = gapsData?.data ?? [];
  const totalGaps  = gapsData?.page?.totalElements ?? 0;
  const totalPages = gapsData?.page?.totalPages    ?? 0;

  return (
    <div>
      <PageHeader title="Certification Readiness" />

      {/* KPI row */}
      <div className={styles.kpiRow}>
        <div className={styles.kpiCard}>
          <span className={styles.kpiLabel}>Readiness</span>
          <span className={styles.kpiValue}>
            {aggregate?.applicable && aggregate?.readinessPercent != null
              ? `${aggregate.readinessPercent}%`
              : 'N/A'}
          </span>
        </div>
        <div className={styles.kpiCard}>
          <span className={styles.kpiLabel}>Gate target</span>
          <span className={styles.kpiValue}>{aggregate?.gateTarget ?? 100}%</span>
        </div>
        <div className={[styles.kpiCard, aggregate?.gateMet ? styles.kpiGreen : styles.kpiRed].join(' ')}>
          <span className={styles.kpiLabel}>Gate</span>
          <span className={styles.kpiValue}>{aggregate?.gateMet ? 'MET' : 'NOT MET'}</span>
        </div>
        <div className={styles.kpiCard}>
          <span className={styles.kpiLabel}>Blocking</span>
          <span className={styles.kpiValue}>{aggregate?.blockingTechnicianCount ?? 0}</span>
        </div>
      </div>

      {/* Trend chart */}
      {chartData.length > 0 && (
        <div className={styles.chartContainer}>
          <h3>Weekly Readiness Trend</h3>
          <ResponsiveContainer width="100%" height={200}>
            <LineChart data={chartData}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="week" />
              <YAxis domain={[0, 100]} unit="%" />
              <Tooltip formatter={(v) => [`${v}%`, 'Readiness']} />
              <Line type="monotone" dataKey="readiness" stroke="#2563eb" dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}

      {/* Gap table */}
      <div className={styles.section}>
        <div className={styles.sectionHeader}>
          <h3>Blocking Technicians ({totalGaps})</h3>
          <a href={gapsCsvUrl()} download className={styles.csvLink}>
            Download CSV
          </a>
        </div>

        {gapsLoading ? (
          <LoadingState />
        ) : gaps.length === 0 ? (
          <EmptyState message="No blocking technicians — gate is met." />
        ) : (
          <>
            <DataTable columns={GAP_COLUMNS} rows={gaps} rowKey="technicianId" />
            <PaginationBar
              page={gapPage}
              totalPages={totalPages}
              onPageChange={setGapPage}
            />
          </>
        )}
      </div>

      {/* Admin actions */}
      {isAdmin && (
        <div className={styles.section}>
          <h3>Snapshot</h3>
          <Button
            onClick={() => snapshotMutation.mutate()}
            disabled={snapshotMutation.isPending}
          >
            {snapshotMutation.isPending ? 'Generating…' : 'Generate Snapshot'}
          </Button>
          {snapshotMutation.isError && (
            <p className={styles.errorText}>Failed to generate snapshot.</p>
          )}
        </div>
      )}
    </div>
  );
}
