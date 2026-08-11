/**
 * @fileoverview Sites admin screen — paginated list, create/edit drawer.
 * @module features/admin/sites/SitesPage
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
import { listSites, createSite, updateSite } from '../../../api/refdata.js'

const KNOWN_FIELDS = ['name', 'reference', 'addressLine1', 'addressLine2', 'city', 'postcode', 'country']

/** @type {import('../../../components/DataTable/DataTable.jsx').ColumnDef[]} */
const COLUMNS = [
  { key: 'name', header: 'Name', sortable: true },
  { key: 'reference', header: 'Reference' },
  { key: 'city', header: 'City' },
  { key: 'postcode', header: 'Postcode' },
  { key: 'active', header: 'Status', render: v => v ? 'Active' : 'Inactive' },
]

const EMPTY_FORM = { name: '', reference: '', addressLine1: '', addressLine2: '', city: '', postcode: '', country: '' }

export default function SitesPage() {
  const { roles } = useAuth()
  const qc = useQueryClient()
  const { state, setPage } = useUrlPageState({ defaultSort: 'name:ASC' })

  const [drawerOpen, setDrawerOpen] = useState(false)
  const [editTarget, setEditTarget] = useState(null)
  const [form, setForm] = useState(EMPTY_FORM)
  const [submitError, setSubmitError] = useState(null)

  const canWrite = roles.some(r => ['ADMIN', 'MANAGER'].includes(r))

  const { rows, page, isLoading, isError, error, refetch } = usePagedQuery({
    queryKey: ['admin', 'sites', state],
    queryFn: ({ signal }) => listSites({ ...state, signal }),
  })

  const { getFieldErrors, summaryErrors } = useFieldErrors(submitError, KNOWN_FIELDS)

  const mutation = useMutation({
    mutationFn: (body) => editTarget ? updateSite(editTarget.id, body) : createSite(body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin', 'sites'] })
      setDrawerOpen(false)
      setEditTarget(null)
      setForm(EMPTY_FORM)
      setSubmitError(null)
    },
    onError: (err) => setSubmitError(err),
  })

  function openCreate() {
    setEditTarget(null); setForm(EMPTY_FORM); setSubmitError(null); setDrawerOpen(true)
  }

  function openEdit(row) {
    setEditTarget(row)
    setForm({ name: row.name ?? '', reference: row.reference ?? '', addressLine1: row.addressLine1 ?? '',
      addressLine2: row.addressLine2 ?? '', city: row.city ?? '', postcode: row.postcode ?? '', country: row.country ?? '' })
    setSubmitError(null); setDrawerOpen(true)
  }

  function handleSubmit(e) {
    e.preventDefault()
    mutation.mutate(form)
  }

  if (isLoading) return <LoadingState />
  if (isError) return <ErrorState onRetry={refetch} message={error?.message} />

  return (
    <div style={{ padding: 'var(--token-space-6)', maxWidth: '1440px', margin: '0 auto' }}>
      <PageHeader title="Sites" actions={canWrite ? <Button onClick={openCreate} variant="primary">Add site</Button> : null} />

      {rows.length === 0 ? <EmptyState /> : (
        <>
          <DataTable columns={COLUMNS} rows={rows} rowKey={r => r.id}
            onRowClick={canWrite ? openEdit : undefined}
            caption="Sites list" aria-label="Sites" />
          <Pagination page={page} onPageChange={setPage} />
        </>
      )}

      <DetailDrawer open={drawerOpen} onClose={() => setDrawerOpen(false)}
        title={editTarget ? 'Edit site' : 'Add site'}>
        <form onSubmit={handleSubmit} noValidate style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-4)' }}>
          <ErrorSummary errors={summaryErrors} traceId={submitError?.traceId} />

          {[
            { field: 'name', label: 'Name', required: true },
            { field: 'reference', label: 'Reference' },
            { field: 'addressLine1', label: 'Address line 1' },
            { field: 'addressLine2', label: 'Address line 2' },
            { field: 'city', label: 'City' },
            { field: 'postcode', label: 'Postcode' },
            { field: 'country', label: 'Country' },
          ].map(({ field, label, required }) => (
            <FormField key={field} label={label} required={required} errors={getFieldErrors(field)}>
              {(inputProps) => (
                <input {...inputProps} type="text" value={form[field]}
                  onChange={e => setForm(f => ({ ...f, [field]: e.target.value }))}
                  style={{ minHeight: '44px', padding: '0 var(--token-space-3)', border: '1px solid var(--token-border-default)', borderRadius: 'var(--token-radius-control)', width: '100%' }} />
              )}
            </FormField>
          ))}

          <div style={{ display: 'flex', gap: 'var(--token-space-3)', justifyContent: 'flex-end' }}>
            <Button type="button" variant="secondary" onClick={() => setDrawerOpen(false)}>Cancel</Button>
            <Button type="submit" variant="primary" disabled={mutation.isPending}>
              {mutation.isPending ? 'Saving…' : (editTarget ? 'Save changes' : 'Add site')}
            </Button>
          </div>
        </form>
      </DetailDrawer>
    </div>
  )
}
