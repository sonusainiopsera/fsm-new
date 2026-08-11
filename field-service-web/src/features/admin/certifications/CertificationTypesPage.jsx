/**
 * @fileoverview Certification types admin screen.
 *
 * ADMIN-only write; MANAGER and DISPATCHER read.
 * Placeholder types are clearly labelled. The regulated flag drives the
 * dispatch hard gate — displayed as an icon+text badge (AC-7).
 *
 * @module features/admin/certifications/CertificationTypesPage
 */
import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../../app/AuthContext.js'
import { PageHeader, DataTable, DetailDrawer, FormField, Button } from '../../../components/index.js'
import { LoadingState, ErrorState, EmptyState } from '../../../components/index.js'
import { usePagedQuery } from '../../../shared/hooks/usePagedQuery.js'
import { useUrlPageState } from '../../../shared/hooks/useUrlPageState.js'
import { useFieldErrors, ErrorSummary } from '../../../shared/forms/useFieldErrors.js'
import { Pagination } from '../../../shared/components/Pagination.jsx'
import { listCertificationTypes, createCertificationType, updateCertificationType } from '../../../api/refdata.js'

const KNOWN_FIELDS = ['code', 'displayName', 'regulated', 'defaultValidityMonths', 'active']

/** @type {import('../../../components/DataTable/DataTable.jsx').ColumnDef[]} */
const COLUMNS = [
  { key: 'code', header: 'Code', sortable: true },
  { key: 'displayName', header: 'Name' },
  {
    key: 'regulated',
    header: 'Gate',
    render: v => v
      ? <span aria-label="Regulated — dispatch hard gate" title="Regulated: dispatch hard gate">
          <span aria-hidden="true">🔒</span>{' '}Regulated
        </span>
      : <span aria-label="Advisory — no dispatch hard gate" title="Advisory: no hard gate">
          <span aria-hidden="true">ℹ</span>{' '}Advisory
        </span>
  },
  {
    key: 'defaultValidityMonths',
    header: 'Validity',
    render: v => v ? `${v} months` : 'Perpetual',
    numeric: true,
  },
  {
    key: 'active',
    header: 'Status',
    render: v => v ? 'Active' : <span style={{ color: 'var(--token-text-secondary)' }}>Inactive</span>,
  },
]

const EMPTY_FORM = { code: '', displayName: '', regulated: false, defaultValidityMonths: '', active: true }

export default function CertificationTypesPage() {
  const { roles } = useAuth()
  const qc = useQueryClient()
  const { state, setPage } = useUrlPageState()

  const [drawerOpen, setDrawerOpen] = useState(false)
  const [editTarget, setEditTarget] = useState(null)
  const [form, setForm] = useState(EMPTY_FORM)
  const [submitError, setSubmitError] = useState(null)

  const isAdmin = roles.includes('ADMIN')
  const canWrite = isAdmin

  const { rows, page, isLoading, isError, error, refetch } = usePagedQuery({
    queryKey: ['admin', 'certification-types', state],
    queryFn: ({ signal }) => listCertificationTypes({ ...state, signal }),
  })

  const { getFieldErrors, summaryErrors } = useFieldErrors(submitError, KNOWN_FIELDS)

  const mutation = useMutation({
    mutationFn: (body) => editTarget
      ? updateCertificationType(editTarget.id, body)
      : createCertificationType(body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin', 'certification-types'] })
      setDrawerOpen(false)
      setEditTarget(null)
      setForm(EMPTY_FORM)
      setSubmitError(null)
    },
    onError: (err) => setSubmitError(err),
  })

  function openCreate() {
    setEditTarget(null)
    setForm(EMPTY_FORM)
    setSubmitError(null)
    setDrawerOpen(true)
  }

  function openEdit(row) {
    setEditTarget(row)
    setForm({
      code: row.code ?? '',
      displayName: row.displayName ?? '',
      regulated: row.regulated ?? false,
      defaultValidityMonths: row.defaultValidityMonths ?? '',
      active: row.active ?? true,
    })
    setSubmitError(null)
    setDrawerOpen(true)
  }

  function handleSubmit(e) {
    e.preventDefault()
    const body = {
      code: form.code,
      displayName: form.displayName,
      regulated: form.regulated,
      defaultValidityMonths: form.defaultValidityMonths ? Number(form.defaultValidityMonths) : null,
      active: form.active,
    }
    mutation.mutate(body)
  }

  if (isLoading) return <LoadingState />
  if (isError) return <ErrorState onRetry={refetch} message={error?.message} />

  return (
    <div style={{ padding: 'var(--token-space-6)', maxWidth: '1440px', margin: '0 auto' }}>
      <PageHeader
        title="Certification Types"
        description="Unratified taxonomy — all types are placeholders until ratification."
        actions={canWrite ? (
          <Button onClick={openCreate} variant="primary">Add type</Button>
        ) : null}
      />

      {rows.length === 0
        ? <EmptyState />
        : (
          <>
            <DataTable
              columns={COLUMNS}
              rows={rows}
              rowKey={r => r.id}
              onRowClick={canWrite ? openEdit : undefined}
              caption="Certification types list"
              aria-label="Certification types"
            />
            <Pagination page={page} onPageChange={setPage} />
          </>
        )
      }

      <DetailDrawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        title={editTarget ? 'Edit certification type' : 'Add certification type'}
      >
        <form onSubmit={handleSubmit} noValidate style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-4)' }}>
          <ErrorSummary errors={summaryErrors} traceId={submitError?.traceId} />

          <FormField label="Code" required errors={getFieldErrors('code')} helpText="UPPER_SNAKE_CASE, e.g. GAS_SAFE">
            {(inputProps) => (
              <input
                {...inputProps}
                type="text"
                value={form.code}
                disabled={!!editTarget}
                onChange={e => setForm(f => ({ ...f, code: e.target.value }))}
                style={{ minHeight: '44px', padding: '0 var(--token-space-3)', border: '1px solid var(--token-border-default)', borderRadius: 'var(--token-radius-control)', width: '100%' }}
              />
            )}
          </FormField>

          <FormField label="Display name" required errors={getFieldErrors('displayName')}>
            {(inputProps) => (
              <input
                {...inputProps}
                type="text"
                value={form.displayName}
                onChange={e => setForm(f => ({ ...f, displayName: e.target.value }))}
                style={{ minHeight: '44px', padding: '0 var(--token-space-3)', border: '1px solid var(--token-border-default)', borderRadius: 'var(--token-radius-control)', width: '100%' }}
              />
            )}
          </FormField>

          <FormField label="Default validity (months)" errors={getFieldErrors('defaultValidityMonths')} helpText="Leave blank for perpetual">
            {(inputProps) => (
              <input
                {...inputProps}
                type="number"
                min="1"
                value={form.defaultValidityMonths}
                onChange={e => setForm(f => ({ ...f, defaultValidityMonths: e.target.value }))}
                style={{ minHeight: '44px', padding: '0 var(--token-space-3)', border: '1px solid var(--token-border-default)', borderRadius: 'var(--token-radius-control)', width: '100%' }}
              />
            )}
          </FormField>

          <FormField label="Regulated (dispatch hard gate)" errors={getFieldErrors('regulated')}>
            {(inputProps) => (
              <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--token-space-2)', minHeight: '44px', cursor: 'pointer' }}>
                <input
                  {...inputProps}
                  type="checkbox"
                  checked={form.regulated}
                  onChange={e => setForm(f => ({ ...f, regulated: e.target.checked }))}
                  style={{ width: '18px', height: '18px' }}
                />
                Regulated — triggers hard dispatch gate (no override)
              </label>
            )}
          </FormField>

          {editTarget && (
            <FormField label="Active" errors={getFieldErrors('active')}>
              {(inputProps) => (
                <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--token-space-2)', minHeight: '44px', cursor: 'pointer' }}>
                  <input
                    {...inputProps}
                    type="checkbox"
                    checked={form.active}
                    onChange={e => setForm(f => ({ ...f, active: e.target.checked }))}
                    style={{ width: '18px', height: '18px' }}
                  />
                  Active (uncheck to deactivate — existing records are preserved)
                </label>
              )}
            </FormField>
          )}

          <div style={{ display: 'flex', gap: 'var(--token-space-3)', justifyContent: 'flex-end' }}>
            <Button type="button" variant="secondary" onClick={() => setDrawerOpen(false)}>Cancel</Button>
            <Button type="submit" variant="primary" disabled={mutation.isPending}>
              {mutation.isPending ? 'Saving…' : (editTarget ? 'Save changes' : 'Add type')}
            </Button>
          </div>
        </form>
      </DetailDrawer>
    </div>
  )
}
