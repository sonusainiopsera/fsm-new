/**
 * @fileoverview Technicians admin screen — paginated list, create/edit drawer, CSV skill import.
 *
 * @module features/admin/technicians/TechniciansPage
 */
import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../../../app/AuthContext.js'
import { PageHeader, DataTable, DetailDrawer, FormField, Button } from '../../../components/index.js'
import { LoadingState, ErrorState, EmptyState } from '../../../components/index.js'
import { usePagedQuery } from '../../../shared/hooks/usePagedQuery.js'
import { useUrlPageState } from '../../../shared/hooks/useUrlPageState.js'
import { useFieldErrors, ErrorSummary } from '../../../shared/forms/useFieldErrors.js'
import { Pagination } from '../../../shared/components/Pagination.jsx'
import { CsvImportWizard } from '../import/CsvImportWizard.jsx'
import { listTechnicians, createTechnician, updateTechnician } from '../../../api/refdata.js'

const ALLOWED_SORT = ['displayName', 'employeeNo', 'createdAt']
const KNOWN_FIELDS = ['employeeNo', 'displayName', 'timezone']

/** @type {import('../../../components/DataTable/DataTable.jsx').ColumnDef[]} */
const COLUMNS = [
  { key: 'employeeNo', header: 'Employee No.', sortable: true },
  { key: 'displayName', header: 'Name', sortable: true },
  { key: 'timezone', header: 'Timezone' },
  { key: 'active', header: 'Status', render: v => v ? 'Active' : 'Inactive' },
]

const EMPTY_FORM = { employeeNo: '', displayName: '', timezone: 'UTC' }

export default function TechniciansPage() {
  const { roles } = useAuth()
  const qc = useQueryClient()
  const navigate = useNavigate()
  const { state, setPage, setSort } = useUrlPageState({ defaultSort: 'displayName:ASC' })

  const [drawerOpen, setDrawerOpen] = useState(false)
  const [editTarget, setEditTarget] = useState(null)
  const [form, setForm] = useState(EMPTY_FORM)
  const [submitError, setSubmitError] = useState(null)
  const [csvTarget, setCsvTarget] = useState(/** @type {string | null} */ (null))
  const [csvOpen, setCsvOpen] = useState(false)

  const canWrite = roles.some(r => ['ADMIN', 'MANAGER'].includes(r))

  const { rows, page, isLoading, isError, error, refetch } = usePagedQuery({
    queryKey: ['admin', 'technicians', state],
    queryFn: ({ signal }) => listTechnicians({ ...state, signal }),
  })

  const { getFieldErrors, summaryErrors } = useFieldErrors(submitError, KNOWN_FIELDS)

  const mutation = useMutation({
    mutationFn: (body) => editTarget
      ? updateTechnician(editTarget.id, body)
      : createTechnician(body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin', 'technicians'] })
      setDrawerOpen(false)
      setEditTarget(null)
      setForm(EMPTY_FORM)
      setSubmitError(null)
    },
    onError: (err) => setSubmitError(err),
  })

  function handleSortChange(field) {
    if (!ALLOWED_SORT.includes(field)) return
    setSort(state.sort?.startsWith(field)
      ? (state.sort === `${field}:ASC` ? `${field}:DESC` : undefined)
      : `${field}:ASC`)
  }

  function handleRowClick(row) {
    if (!canWrite) return
    setEditTarget(row)
    setForm({ employeeNo: row.employeeNo ?? '', displayName: row.displayName ?? '', timezone: row.timezone ?? 'UTC' })
    setSubmitError(null)
    setDrawerOpen(true)
  }

  function handleSubmit(e) {
    e.preventDefault()
    mutation.mutate(form)
  }

  if (isLoading) return <LoadingState />
  if (isError) return <ErrorState onRetry={refetch} message={error?.message} />

  return (
    <div style={{ padding: 'var(--token-space-6)', maxWidth: '1440px', margin: '0 auto' }}>
      <PageHeader
        title="Technicians"
        actions={canWrite ? (
          <Button onClick={() => { setEditTarget(null); setForm(EMPTY_FORM); setSubmitError(null); setDrawerOpen(true) }} variant="primary">
            Add technician
          </Button>
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
              onRowClick={handleRowClick}
              caption="Technicians list"
              aria-label="Technicians"
              actions={canWrite ? (row) => (
                <div style={{ display: 'flex', gap: 'var(--token-space-2)' }}>
                  <Button
                    type="button"
                    variant="secondary"
                    onClick={(e) => {
                      e.stopPropagation()
                      navigate(`/admin/technicians/${row.id}/certifications`)
                    }}
                    style={{ minHeight: '44px' }}
                  >
                    Certifications
                  </Button>
                  <Button
                    type="button"
                    variant="secondary"
                    onClick={(e) => {
                      e.stopPropagation()
                      setCsvTarget(row.id)
                      setCsvOpen(true)
                    }}
                    style={{ minHeight: '44px' }}
                  >
                    Import skills
                  </Button>
                </div>
              ) : undefined}
            />
            <Pagination page={page} onPageChange={setPage} />
          </>
        )
      }

      {/* Create/edit drawer */}
      <DetailDrawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        title={editTarget ? 'Edit technician' : 'Add technician'}
      >
        <form onSubmit={handleSubmit} noValidate style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-4)' }}>
          <ErrorSummary errors={summaryErrors} traceId={submitError?.traceId} />

          <FormField label="Employee number" required errors={getFieldErrors('employeeNo')}>
            {(inputProps) => (
              <input {...inputProps} type="text" value={form.employeeNo} disabled={!!editTarget}
                onChange={e => setForm(f => ({ ...f, employeeNo: e.target.value }))}
                style={{ minHeight: '44px', padding: '0 var(--token-space-3)', border: '1px solid var(--token-border-default)', borderRadius: 'var(--token-radius-control)', width: '100%' }} />
            )}
          </FormField>

          <FormField label="Display name" errors={getFieldErrors('displayName')}>
            {(inputProps) => (
              <input {...inputProps} type="text" value={form.displayName}
                onChange={e => setForm(f => ({ ...f, displayName: e.target.value }))}
                style={{ minHeight: '44px', padding: '0 var(--token-space-3)', border: '1px solid var(--token-border-default)', borderRadius: 'var(--token-radius-control)', width: '100%' }} />
            )}
          </FormField>

          <FormField label="Timezone" errors={getFieldErrors('timezone')}>
            {(inputProps) => (
              <input {...inputProps} type="text" value={form.timezone}
                onChange={e => setForm(f => ({ ...f, timezone: e.target.value }))}
                placeholder="e.g. Europe/London"
                style={{ minHeight: '44px', padding: '0 var(--token-space-3)', border: '1px solid var(--token-border-default)', borderRadius: 'var(--token-radius-control)', width: '100%' }} />
            )}
          </FormField>

          <div style={{ display: 'flex', gap: 'var(--token-space-3)', justifyContent: 'flex-end' }}>
            <Button type="button" variant="secondary" onClick={() => setDrawerOpen(false)}>Cancel</Button>
            <Button type="submit" variant="primary" disabled={mutation.isPending}>
              {mutation.isPending ? 'Saving…' : (editTarget ? 'Save changes' : 'Add technician')}
            </Button>
          </div>
        </form>
      </DetailDrawer>

      {/* CSV skill import wizard */}
      {csvTarget && (
        <CsvImportWizard
          open={csvOpen}
          onClose={() => { setCsvOpen(false); setCsvTarget(null) }}
          technicianId={csvTarget}
          importType="skills"
        />
      )}
    </div>
  )
}
