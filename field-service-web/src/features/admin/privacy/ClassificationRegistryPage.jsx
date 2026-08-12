/**
 * @fileoverview Classification Registry — paginated table with inline edit.
 *
 * AC-2: Allow-listed sorting, server-side pagination at max 50.
 * AC-2: Edit dialog submits versioned PUT; 409 conflict refetches the row
 *       and requires the user to re-apply the change.
 * AC-8: Composes from Phase 1 design tokens only — no hard-coded values.
 *
 * @module features/admin/privacy/ClassificationRegistryPage
 */
import { useState, useCallback } from 'react'
import { useAuth } from '../../../app/AuthContext.js'
import {
  PageHeader, DataTable, Modal, FormField, Button,
  LoadingState, ErrorState, EmptyState, PermissionDeniedState,
} from '../../../components/index.js'
import { Pagination } from '../../../shared/components/Pagination.jsx'
import { useUrlPageState } from '../../../shared/hooks/useUrlPageState.js'
import { ErrorSummary } from '../../../shared/forms/useFieldErrors.js'
import { useClassifications, useUpdateClassification } from '../../privacy/hooks/useClassifications.js'

const ALLOWED_SORT = ['entityName', 'fieldName', 'module', 'tier']

const TIER_LABELS = {
  PUBLIC: 'Public',
  INTERNAL: 'Internal',
  CONFIDENTIAL: 'Confidential',
  RESTRICTED: 'Restricted',
}

/** @type {import('../../../components/DataTable/DataTable.jsx').ColumnDef[]} */
const COLUMNS = [
  { key: 'module', header: 'Module', sortable: true },
  { key: 'entityName', header: 'Entity', sortable: true },
  { key: 'fieldName', header: 'Field', sortable: true },
  { key: 'tier', header: 'Tier', sortable: true, render: v => TIER_LABELS[v] ?? v },
  { key: 'handlingNotes', header: 'Handling Notes', render: v => v ?? '—' },
]

export default function ClassificationRegistryPage() {
  const { roles } = useAuth()
  const canWrite = roles.some(r => ['PRIVACY_ADMIN', 'ADMIN'].includes(r))

  if (!canWrite) {
    return <PermissionDeniedState />
  }

  return <ClassificationRegistryContent canWrite={canWrite} />
}

/** @param {{ canWrite: boolean }} props */
function ClassificationRegistryContent({ canWrite }) {
  const { state, setPage, setSort } = useUrlPageState({ defaultSort: 'entityName:ASC' })
  const { rows, page, isLoading, isError, error, refetch } = useClassifications({
    page: state.page,
    size: state.size,
    sort: state.sort,
  })

  const [editTarget, setEditTarget] = useState(/** @type {Record<string, unknown> | null} */ (null))
  const [form, setForm] = useState({ tier: '', handlingNotes: '' })
  const [submitError, setSubmitError] = useState(/** @type {import('../../../api/errors.js').ClientError | null} */ (null))
  const [conflictRow, setConflictRow] = useState(/** @type {Record<string, unknown> | null} */ (null))

  const updateMutation = useUpdateClassification()

  const openEdit = useCallback((row) => {
    setEditTarget(row)
    setForm({ tier: row.tier ?? '', handlingNotes: row.handlingNotes ?? '' })
    setSubmitError(null)
    setConflictRow(null)
  }, [])

  const closeEdit = useCallback(() => {
    setEditTarget(null)
    setSubmitError(null)
    setConflictRow(null)
  }, [])

  function handleSortChange(field) {
    if (!ALLOWED_SORT.includes(field)) return
    setSort(state.sort?.startsWith(field)
      ? (state.sort === `${field}:ASC` ? `${field}:DESC` : undefined)
      : `${field}:ASC`)
  }

  function handleSubmit(e) {
    e.preventDefault()
    if (!editTarget) return
    updateMutation.mutate(
      {
        id: editTarget.id,
        body: {
          tier: form.tier,
          handlingNotes: form.handlingNotes || null,
          version: editTarget.version,
        },
      },
      {
        onSuccess: closeEdit,
        onError: (err) => {
          if (err.status === 409) {
            // Conflict: refetch and require re-apply
            setConflictRow(editTarget)
            refetch()
          }
          setSubmitError(err)
        },
      }
    )
  }

  if (isLoading) return <LoadingState />
  if (isError) return <ErrorState onRetry={refetch} message={error?.message} />

  return (
    <div style={{ padding: 'var(--token-space-6)', maxWidth: '1440px', margin: '0 auto' }}>
      <PageHeader title="Classification Registry" />

      <p
        aria-live="polite"
        style={{
          fontSize: 'var(--token-fs-14)',
          color: 'var(--token-text-secondary)',
          marginBottom: 'var(--token-space-4)',
        }}
      >
        Data classification tiers control which fields may be rectified or erased under subject-rights requests.
      </p>

      {rows.length === 0
        ? <EmptyState message="No classified fields found." />
        : (
          <>
            <DataTable
              columns={COLUMNS}
              rows={rows}
              rowKey={r => r.id}
              onRowClick={canWrite ? openEdit : undefined}
              caption="Data classification registry"
              aria-label="Classification registry"
              onSortChange={handleSortChange}
              sortKey={state.sort?.split(':')[0]}
              sortDir={state.sort?.split(':')[1]}
            />
            <Pagination page={page} onPageChange={setPage} />
          </>
        )
      }

      {/* Edit modal */}
      <Modal
        open={editTarget != null}
        onClose={closeEdit}
        title={`Edit: ${editTarget?.entityName}.${editTarget?.fieldName}`}
        size="sm"
      >
        <form onSubmit={handleSubmit} noValidate style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-4)' }}>
          {conflictRow && (
            <div
              role="alert"
              aria-live="assertive"
              style={{
                background: 'var(--token-warning-subtle)',
                border: '1px solid var(--token-warning-default)',
                borderRadius: 'var(--token-radius-control)',
                padding: 'var(--token-space-3)',
                fontSize: 'var(--token-fs-14)',
                color: 'var(--token-warning-emphasis)',
              }}
            >
              ⚠ This row was changed by another administrator. The form has been updated — please review and re-apply.
            </div>
          )}

          <ErrorSummary
            errors={submitError && submitError.status !== 409 ? [{ message: submitError.message }] : []}
            traceId={submitError?.traceId}
          />

          <FormField label="Classification Tier" required>
            {(inputProps) => (
              <select
                {...inputProps}
                value={form.tier}
                onChange={e => setForm(f => ({ ...f, tier: e.target.value }))}
                style={{
                  minHeight: '44px',
                  padding: '0 var(--token-space-3)',
                  border: '1px solid var(--token-border-default)',
                  borderRadius: 'var(--token-radius-control)',
                  width: '100%',
                  background: 'var(--token-surface-default)',
                  color: 'var(--token-text-primary)',
                  fontSize: 'var(--token-fs-14)',
                  fontFamily: 'var(--token-family-base)',
                }}
              >
                {Object.entries(TIER_LABELS).map(([value, label]) => (
                  <option key={value} value={value}>{label}</option>
                ))}
              </select>
            )}
          </FormField>

          <FormField label="Handling Notes">
            {(inputProps) => (
              <textarea
                {...inputProps}
                value={form.handlingNotes}
                onChange={e => setForm(f => ({ ...f, handlingNotes: e.target.value }))}
                rows={3}
                style={{
                  padding: 'var(--token-space-2) var(--token-space-3)',
                  border: '1px solid var(--token-border-default)',
                  borderRadius: 'var(--token-radius-control)',
                  width: '100%',
                  resize: 'vertical',
                  background: 'var(--token-surface-default)',
                  color: 'var(--token-text-primary)',
                  fontSize: 'var(--token-fs-14)',
                  fontFamily: 'var(--token-family-base)',
                }}
              />
            )}
          </FormField>

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
