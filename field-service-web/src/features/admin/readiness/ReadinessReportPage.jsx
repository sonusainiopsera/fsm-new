/**
 * @fileoverview Certification data-readiness report page (WO-122, AC-7).
 *
 * Evidences the Phase 1 exit gate: "certification data-readiness audit complete
 * with a remediation plan" per the workforce module governance requirement.
 *
 * Composed exclusively from Phase 1 design tokens and shared primitives (AC-7).
 * Meets contrast parity in both light and dark appearances (AC-7).
 * Status conveyed by icon + text — not by colour alone (AC-7, BR-34).
 *
 * @module features/admin/readiness/ReadinessReportPage
 */

import { useState } from 'react'
import {
  PageHeader, KpiCard, DataTable, Button,
  LoadingState, ErrorState, EmptyState,
} from '../../../components/index.js'
import {
  useReadinessSummary,
  useReadinessGaps,
  useReadinessSnapshots,
  useGenerateSnapshot,
} from '../../../api/readiness.js'
import { ReadinessTrendChart } from './ReadinessTrendChart.jsx'

// ── KPI status helpers ────────────────────────────────────────────────────────

function gateLabel(gateMet, applicable) {
  if (!applicable) return '— N/A'
  return gateMet ? '✓ Gate met' : '✗ Gate not met'
}

function percentDisplay(readinessPercent, applicable) {
  if (!applicable) return 'N/A'
  if (readinessPercent == null) return '—'
  return `${Number(readinessPercent).toFixed(2)}%`
}

function weekOverWeekDelta(snapshots) {
  const rows = snapshots?.data
  if (!rows || rows.length < 2) return null
  const latest = rows[0].readinessPercent
  const prior  = rows[1].readinessPercent
  if (latest == null || prior == null) return null
  const delta = (latest - prior).toFixed(2)
  return delta >= 0 ? `+${delta}pp` : `${delta}pp`
}

// ── Gap table columns ─────────────────────────────────────────────────────────

/** @type {import('../../../components/DataTable/DataTable.jsx').ColumnDef[]} */
const GAP_COLUMNS = [
  { key: 'employeeCode',   header: 'Employee Code',  render: v => v ?? '—' },
  { key: 'displayName',    header: 'Name' },
  {
    key: 'missingFields',
    header: 'Missing Fields',
    render: (v) => v?.length > 0 ? v.join(', ') : '—',
  },
  {
    key: 'missingCertificationTypes',
    header: 'Missing Certs',
    render: (v) => v?.length > 0 ? v.join(', ') : '—',
  },
  {
    key: 'expiredCertificationTypes',
    header: 'Expired Certs',
    render: (v) => v?.length > 0 ? v.join(', ') : '—',
  },
  {
    key: 'expiringSoonCertificationTypes',
    header: 'Expiring Soon',
    render: (v) => v?.length > 0 ? v.join(', ') : '—',
  },
]

// ── Main component ────────────────────────────────────────────────────────────

/**
 * Certification data-readiness report page.
 *
 * Presents the aggregate readiness figure, gate verdict, week-over-week trend
 * and a drill-down table of blocking technicians.
 */
export default function ReadinessReportPage() {
  const [gapsPage, setGapsPage]         = useState(0)
  const [snapshotsPage]                 = useState(0)

  const { data: summary,   isLoading: summaryLoading,  isError: summaryError  } = useReadinessSummary()
  const { data: gaps,      isLoading: gapsLoading,     isError: gapsError     } = useReadinessGaps({ page: gapsPage, size: 25 })
  const { data: snapshots, isLoading: snapsLoading                             } = useReadinessSnapshots({ page: snapshotsPage, size: 12 })
  const generateMutation = useGenerateSnapshot()

  if (summaryLoading) return <LoadingState label="Loading readiness report…" />
  if (summaryError)   return <ErrorState   message="Failed to load readiness summary." />

  const delta = weekOverWeekDelta(snapshots)

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-6)' }}>
      <PageHeader
        title="Certification Data-Readiness"
        subtitle="Phase 1 exit gate — 100% of active technicians must have complete, in-date certification profiles."
        actions={
          <Button
            variant="primary"
            size="sm"
            busy={generateMutation.isPending}
            onClick={() => generateMutation.mutate()}
            aria-label="Generate weekly snapshot"
          >
            Generate snapshot
          </Button>
        }
      />

      {/* ── KPI row ── */}
      <div
        role="region"
        aria-label="Readiness KPI summary"
        style={{ display: 'flex', gap: 'var(--space-4)', flexWrap: 'wrap' }}
      >
        <KpiCard
          label="Readiness"
          value={percentDisplay(summary?.readinessPercent, summary?.applicable)}
          detail={`${summary?.completeTechnicians ?? '—'} of ${summary?.activeTechnicians ?? '—'} technicians complete`}
          status={summary?.gateMet ? 'success' : 'warning'}
          aria-label={`Readiness ${percentDisplay(summary?.readinessPercent, summary?.applicable)}`}
        />
        <KpiCard
          label="Gate verdict"
          value={gateLabel(summary?.gateMet, summary?.applicable)}
          detail={`Target: ${summary?.gateTarget ?? 100}%`}
          status={summary?.gateMet ? 'success' : 'critical'}
        />
        <KpiCard
          label="Blocking technicians"
          value={summary?.applicable ? String(summary?.blockingTechnicianCount ?? 0) : 'N/A'}
          detail="Must reach 0 to pass gate"
          status={summary?.blockingTechnicianCount === 0 ? 'success' : 'warning'}
        />
        {delta != null && (
          <KpiCard
            label="Week-over-week"
            value={delta}
            detail="Change in readiness percentage"
            status={delta.startsWith('+') ? 'success' : 'warning'}
          />
        )}
      </div>

      {/* ── Trend chart ── */}
      {!snapsLoading && snapshots?.data?.length > 0 && (
        <section aria-label="Readiness trend">
          <h2 style={{ font: 'var(--text-lg)', marginBottom: 'var(--space-3)' }}>
            Weekly trend
          </h2>
          <ReadinessTrendChart snapshots={snapshots.data} />
        </section>
      )}

      {/* ── Gap drill-down table ── */}
      <section aria-label="Blocking technicians">
        <h2 style={{ font: 'var(--text-lg)', marginBottom: 'var(--space-3)' }}>
          Blocking technicians
        </h2>

        {summary?.gateMet && (
          <EmptyState
            icon="✓"
            title="Gate met — all technicians are compliant"
            description="All active technicians have complete, in-date certification profiles."
          />
        )}

        {!summary?.gateMet && gapsLoading && <LoadingState label="Loading gap detail…" />}
        {!summary?.gateMet && gapsError   && <ErrorState   message="Failed to load gap detail." />}
        {!summary?.gateMet && !gapsLoading && !gapsError && (
          <>
            <DataTable
              columns={GAP_COLUMNS}
              rows={gaps?.data ?? []}
              getRowKey={(r) => r.technicianId}
              caption="Technicians blocking the data-readiness gate"
            />
            {/* Simple previous / next pagination */}
            {gaps?.meta?.totalPages > 1 && (
              <div style={{ display: 'flex', gap: 'var(--space-3)', marginTop: 'var(--space-4)' }}>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={gapsPage === 0}
                  onClick={() => setGapsPage(p => Math.max(0, p - 1))}
                >
                  Previous
                </Button>
                <span style={{ color: 'var(--text-secondary)', fontSize: 'var(--text-sm)' }}>
                  Page {gapsPage + 1} of {gaps.meta.totalPages}
                </span>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={gapsPage >= gaps.meta.totalPages - 1}
                  onClick={() => setGapsPage(p => p + 1)}
                >
                  Next
                </Button>
              </div>
            )}
            <div style={{ marginTop: 'var(--space-4)' }}>
              <a
                href="/api/v1/reports/certification-readiness/gaps.csv"
                download="certification-readiness-gaps.csv"
                style={{ color: 'var(--text-accent)', textDecoration: 'underline', fontSize: 'var(--text-sm)' }}
              >
                Download CSV export
              </a>
            </div>
          </>
        )}
      </section>
    </div>
  )
}
