/**
 * @fileoverview Retention Schedule — per-category period editor with dry-run.
 *
 * AC-3: Unratified rows carry an explicit placeholder label.
 * AC-4: Dry-run renders eligible count, oldest eligible timestamp, cut-off and
 *       disposal method; explicitly states no data has been changed.
 * AC-8: Design tokens only.
 *
 * @module features/admin/privacy/RetentionSchedulePage
 */
import { useState, useCallback } from 'react'
import { useAuth } from '../../../app/AuthContext.js'
import {
  PageHeader, DataTable, Modal, FormField, Button,
  LoadingState, ErrorState, EmptyState, PermissionDeniedState,
} from '../../../components/index.js'
import { Pagination } from '../../../shared/components/Pagination.jsx'
import { ErrorSummary } from '../../../shared/forms/useFieldErrors.js'
import {
  useRetentionPolicies,
  useUpdateRetentionPolicy,
  useRetentionDryRun,
} from '../../privacy/hooks/useRetentionPolicies.js'

const PERIOD_UNITS = ['DAYS', 'MONTHS', 'YEARS']

/** @type {import('../../../components/DataTable/DataTable.jsx').ColumnDef[]} */
const COLUMNS = [
  { key: 'categoryName', header: 'Category' },
  {
    key: 'retentionPeriod',
    header: 'Period',
    render: (v, row) => row.ratified
      ? `${v} ${row.periodUnit}`
      : `${v} ${row.periodUnit} (indicative — not ratified)`,
  },
  { key: 'anchorField', header: 'Anchor', render: v => v ?? '—' },
  { key: 'disposalMethod', header: 'Disposal' },
  {
    key: 'legalHold',
    header: 'Legal Hold',
    render: v => v ? '⚑ Yes' : 'No',
  },
  {
    key: 'enabled',
    header: 'Enabled',
    render: v => v ? 'Yes' : 'No',
  },
  {
    key: 'ratified',
    header: 'Status',
    render: v => v
      ? 'Ratified'
      : <span style={{ color: 'var(--token-warning-emphasis)', fontStyle: 'italic' }}>Indicative placeholder</span>,
  },
]

export default function RetentionSchedulePage() {
  const { roles } = useAuth()
  const canWrite = roles.some(r => ['PRIVACY_ADMIN', 'ADMIN'].includes(r))

  if (!canWrite) {
    return <PermissionDeniedState />
  }

  return <RetentionScheduleContent />
}

function RetentionScheduleContent() {
  const [currentPage, setCurrentPage] = useState(0)
  const { rows, page, isLoading, isError, error, refetch } = useRetentionPolicies({
    page: currentPage,
    size: 50,
  })

  const [editTarget, setEditTarget] = useState(/** @type {Record<string, unknown> | null} */ (null))
  const [form, setForm] = useState({ retentionPeriod: '', periodUnit: 'YEARS' })
  const [submitError, setSubmitError] = useState(/** @type {import('../../../api/errors.js').ClientError | null} */ (null))
  const [conflictNotice, setConflictNotice] = useState(false)
  const [dryRunResult, setDryRunResult] = useState(/** @type {import('../../privacy/api/privacyClient.js').DryRunReport | null} */ (null))
  const [dryRunTarget, setDryRunTarget] = useState(/** @type {string | null} */ (null))

  const updateMutation = useUpdateRetentionPolicy()
  const dryRunMutation = useRetentionDryRun()

  const openEdit = useCallback((row) => {
    setEditTarget(row)
    setForm({ retentionPeriod: String(row.retentionPeriod ?? ''), periodUnit: row.periodUnit ?? 'YEARS' })
    setSubmitError(null)
    setConflictNotice(false)
    setDryRunResult(null)
  }, [])

  const closeEdit = useCallback(() => {
    setEditTarget(null)
    setSubmitError(null)
    setConflictNotice(false)
    setDryRunResult(null)
  }, [])

  function handleSubmit(e) {
    e.preventDefault()
    if (!editTarget) return
    const period = parseInt(form.retentionPeriod, 10)
    if (!period || period <= 0) {
      setSubmitError({
        status: 400, code: 'VALIDATION_FAILED',
        message: 'Retention period must be a positive number.',
        fieldErrors: [], traceId: null, retryable: false,
      })
      return
    }
    updateMutation.mutate(
      {
        id: editTarget.id,
        body: { retentionPeriod: period, periodUnit: form.periodUnit, version: editTarget.version },
      },
      {
        onSuccess: closeEdit,
        onError: (err) => {
          if (err.status === 409) {
            setConflictNotice(true)
            refetch()
          }
          setSubmitError(err)
        },
      }
    )
  }

  function handleDryRun() {
    if (!editTarget) return
    setDryRunTarget(editTarget.id)
    dryRunMutation.mutate(
      { id: editTarget.id },
      {
        onSuccess: (result) => setDryRunResult(result),
        onError: (err) => setSubmitError(err),
      }
    )
  }

  if (isLoading) return <LoadingState />
  if (isError) return <ErrorState onRetry={refetch} message={error?.message} />

  return (
    <div style={{ padding: 'var(--token-space-6)', maxWidth: '1440px', margin: '0 auto' }}>
      <PageHeader title="Retention Schedule" />

      <p style={{ fontSize: 'var(--token-fs-14)', color: 'var(--token-text-secondary)', marginBottom: 'var(--token-space-4)' }}>
        Unratified rows are indicative placeholders pending DPO ratification and must not be treated as binding targets.
      </p>

      {rows.length === 0
        ? <EmptyState message="No retention policies configured." />
        : (
          <>
            <DataTable
              columns={COLUMNS}
              rows={rows}
              rowKey={r => r.id}
              onRowClick={openEdit}
              caption="Retention schedule"
              aria-label="Retention policies"
            />
            <Pagination page={page} onPageChange={setCurrentPage} />
          </>
        )
      }

      <Modal
        open={editTarget != null}
        onClose={closeEdit}
        title={`Edit: ${editTarget?.categoryName}`}
        size="md"
      >
        <form onSubmit={handleSubmit} noValidate style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-4)' }}>
          {/* Unratified warning */}
          {editTarget && !editTarget.ratified && (
            <div
              role="note"
              style={{
                background: 'var(--token-warning-subtle)',
                border: '1px solid var(--token-warning-default)',
                borderRadius: 'var(--token-radius-control)',
                padding: 'var(--token-space-3)',
                fontSize: 'var(--token-fs-14)',
                color: 'var(--token-warning-emphasis)',
              }}
            >
              ⚠ This row is an indicative placeholder. It has not been ratified by the Data Protection Officer and must not be treated as a binding retention target.
            </div>
          )}

          {conflictNotice && (
            <div role="alert" aria-live="assertive" style={{ background: 'var(--token-warning-subtle)', border: '1px solid var(--token-warning-default)', borderRadius: 'var(--token-radius-control)', padding: 'var(--token-space-3)', fontSize: 'var(--token-fs-14)', color: 'var(--token-warning-emphasis)' }}>
              ⚠ Another administrator changed this row. Please review the updated values and re-apply.
            </div>
          )}

          <ErrorSummary
            errors={submitError && submitError.status !== 409 ? [{ message: submitError.message }] : []}
            traceId={submitError?.traceId}
          />

          <div style={{ display: 'flex', gap: 'var(--token-space-3)', alignItems: 'flex-end' }}>
            <FormField label="Retention Period" required style={{ flex: 1 }}>
              {(inputProps) => (
                <input
                  {...inputProps}
                  type="number"
                  min="1"
                  value={form.retentionPeriod}
                  onChange={e => setForm(f => ({ ...f, retentionPeriod: e.target.value }))}
                  style={{
                    minHeight: '44px',
                    padding: '0 var(--token-space-3)',
                    border: '1px solid var(--token-border-default)',
                    borderRadius: 'var(--token-radius-control)',
                    width: '100%',
                    fontSize: 'var(--token-fs-14)',
                    fontFamily: 'var(--token-family-base)',
                    fontVariantNumeric: 'tabular-nums',
                  }}
                />
              )}
            </FormField>

            <FormField label="Unit" required>
              {(inputProps) => (
                <select
                  {...inputProps}
                  value={form.periodUnit}
                  onChange={e => setForm(f => ({ ...f, periodUnit: e.target.value }))}
                  style={{
                    minHeight: '44px',
                    padding: '0 var(--token-space-3)',
                    border: '1px solid var(--token-border-default)',
                    borderRadius: 'var(--token-radius-control)',
                    background: 'var(--token-surface-default)',
                    color: 'var(--token-text-primary)',
                    fontSize: 'var(--token-fs-14)',
                    fontFamily: 'var(--token-family-base)',
                  }}
                >
                  {PERIOD_UNITS.map(u => <option key={u} value={u}>{u}</option>)}
                </select>
              )}
            </FormField>
          </div>

          {/* Dry-run section */}
          <div style={{
            borderTop: 'var(--token-elevation-border)',
            paddingTop: 'var(--token-space-4)',
          }}>
            <Button
              type="button"
              variant="secondary"
              onClick={handleDryRun}
              disabled={dryRunMutation.isPending}
            >
              {dryRunMutation.isPending ? 'Running preview…' : 'Preview dry-run'}
            </Button>
            <p style={{ fontSize: 'var(--token-fs-13)', color: 'var(--token-text-secondary)', margin: 'var(--token-space-2) 0 0' }}>
              No data will be changed.
            </p>

            {dryRunResult && (
              <div
                data-testid="dry-run-result"
                style={{
                  marginTop: 'var(--token-space-3)',
                  background: 'var(--token-info-subtle)',
                  border: '1px solid var(--token-info-default)',
                  borderRadius: 'var(--token-radius-control)',
                  padding: 'var(--token-space-3)',
                  fontSize: 'var(--token-fs-14)',
                }}
              >
                <p style={{ margin: '0 0 var(--token-space-2)', fontWeight: 600 }}>Dry-run result — no data changed</p>
                <dl style={{ margin: 0, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 'var(--token-space-2)' }}>
                  <dt>Eligible rows</dt>
                  <dd style={{ fontVariantNumeric: 'tabular-nums', margin: 0 }}>{dryRunResult.eligibleCount.toLocaleString()}</dd>
                  <dt>Oldest eligible</dt>
                  <dd style={{ margin: 0 }}>{dryRunResult.oldestEligibleAt
                    ? new Date(dryRunResult.oldestEligibleAt).toLocaleDateString()
                    : '—'}
                  </dd>
                  <dt>Cut-off</dt>
                  <dd style={{ margin: 0 }}>{new Date(dryRunResult.cutoffAt).toLocaleDateString()}</dd>
                  <dt>Disposal method</dt>
                  <dd style={{ margin: 0 }}>{dryRunResult.disposalMethod}</dd>
                </dl>
                {dryRunResult.eligibleCount === 0 && (
                  <p style={{ marginTop: 'var(--token-space-2)', color: 'var(--token-text-secondary)' }}>
                    No records are currently eligible for disposal under this policy.
                  </p>
                )}
              </div>
            )}
          </div>

          <div style={{ display: 'flex', gap: 'var(--token-space-3)', justifyContent: 'flex-end' }}>
            <Button type="button" variant="secondary" onClick={closeEdit}>Cancel</Button>
            <Button type="submit" variant="primary" disabled={updateMutation.isPending}>
              {updateMutation.isPending ? 'Saving…' : 'Save changes'}
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  )
}
