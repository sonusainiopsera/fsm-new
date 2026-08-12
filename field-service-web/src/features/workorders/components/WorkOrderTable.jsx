/**
 * WorkOrderTable — dispatcher board table.
 *
 * Renders work orders using the shared DataTable primitive.
 * Columns: reference, customer+site, priority, state, assigned technician,
 * response deadline, resolution deadline, at-risk indicator.
 *
 * All chip/badge primitives come from the shared library so the same
 * component appears at different densities across surfaces.
 *
 * No screen-local styling. All tokens consumed via CSS custom properties.
 *
 * @module features/workorders/components/WorkOrderTable
 */

import React from 'react';

import { DataTable, Chip } from '../../../components/index.js';

import styles from './WorkOrderTable.module.css';

/**
 * Formats a deadline ISO string into a compact label.
 * Returns null if deadline is null.
 *
 * @param {string | null} isoString
 * @returns {string | null}
 */
function formatDeadline(isoString) {
  if (!isoString) return null;
  const d = new Date(isoString);
  if (isNaN(d.getTime())) return null;
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

/**
 * Countdown cell for a deadline.
 * Uses server-provided deadline string; does not compute deadlines from local clock alone.
 *
 * @param {{ deadline: string | null, label: string }} props
 */
function DeadlineCell({ deadline, label }) {
  if (!deadline) return <span className={styles.noDeadline} aria-label={`No ${label} deadline`}>—</span>;
  const formatted = formatDeadline(deadline);
  return (
    <span className={styles.deadline} title={deadline} aria-label={`${label} deadline: ${formatted}`}>
      {formatted}
    </span>
  );
}

/**
 * At-risk badge — icon + text for non-colour-only encoding (WCAG 2.1 AA, BR-34).
 *
 * @param {{ atRisk: boolean }} props
 */
function AtRiskBadge({ atRisk }) {
  if (!atRisk) return <span className={styles.noRisk} aria-label="On track">—</span>;
  return (
    <span className={styles.atRisk} role="status" aria-label="At risk">
      <span aria-hidden="true">⬥</span>
      {' '}At risk
    </span>
  );
}

/** @type {import('../../../components/DataTable/DataTable.jsx').ColumnDef[]} */
const COLUMNS = [
  {
    key: 'reference',
    header: 'Reference',
    sortable: true,
    render: (v) => <span className={styles.reference}>{v}</span>,
  },
  {
    key: 'customerName',
    header: 'Customer / Site',
    render: (_v, row) => (
      <span className={styles.customerSite}>
        <span className={styles.customerName} title={row.customerName}>{row.customerName}</span>
        {row.siteName && (
          <span className={styles.siteName} title={row.siteName}>{row.siteName}</span>
        )}
      </span>
    ),
  },
  {
    key: 'priority',
    header: 'Priority',
    sortable: true,
    render: (v) => <Chip variant="priority" value={v?.toLowerCase()} />,
  },
  {
    key: 'state',
    header: 'State',
    sortable: true,
    render: (v) => <Chip variant="state" value={v?.toLowerCase()} />,
  },
  {
    key: 'assignedTechnicianName',
    header: 'Technician',
    render: (v) => (
      <span className={styles.techName} title={v ?? 'Unassigned'}>
        {v ?? <span className={styles.unassigned}>Unassigned</span>}
      </span>
    ),
  },
  {
    key: 'responseDeadline',
    header: 'Response by',
    render: (v) => <DeadlineCell deadline={v} label="response" />,
  },
  {
    key: 'resolutionDeadline',
    header: 'Resolve by',
    render: (v) => <DeadlineCell deadline={v} label="resolution" />,
  },
  {
    key: 'atRisk',
    header: 'Risk',
    render: (v) => <AtRiskBadge atRisk={!!v} />,
  },
];

/**
 * @param {{
 *   data: import('../api/useWorkOrderSearch.js').WorkOrderRow[],
 *   sort: import('../api/useWorkOrderSearch.js').SortState | null,
 *   selectedId: string | null,
 *   onRowClick: (row: object) => void,
 *   onSort: (sort: import('../api/useWorkOrderSearch.js').SortState) => void,
 *   emptyState?: React.ReactNode,
 * }} props
 */
export function WorkOrderTable({ data, sort, selectedId, onRowClick, onSort, emptyState }) {
  return (
    <DataTable
      columns={COLUMNS}
      data={data}
      rowKey={(row) => row.id}
      selectedKey={selectedId}
      onRowClick={onRowClick}
      onSort={onSort}
      sort={sort}
      emptyState={emptyState}
      caption="Work orders"
    />
  );
}
