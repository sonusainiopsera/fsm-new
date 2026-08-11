/**
 * @fileoverview Assets admin screen — paginated list, create/edit drawer.
 * @module features/admin/assets/AssetsPage
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
import { listAssets, createAsset, updateAsset } from '../../../api/refdata.js'

const KNOWN_FIELDS = ['serialNumber', 'assetTag', 'description', 'siteId', 'assetTypeCode']

/** @type {import('../../../components/DataTable/DataTable.jsx').ColumnDef[]} */
const COLUMNS = [
  { key: 'serialNumber', header: 'Serial', sortable: true },
  { key: 'assetTag', header: 'Tag' },
  { key: 'assetTypeCode', header: 'Type' },
  { key: 'siteName', header: 'Site' },
  { key: 'active', header: 'Status', render: v => v ? 'Active' : 'Inactive' },
]

const EMPTY_FORM = { serialNumber: '', assetTag: '', description: '', siteId: '', assetTypeCode: '' }

export default function AssetsPage() {
  const { roles } = useAuth()
  const qc = useQueryClient()
  const { state, setPage } = useUrlPageState({ defaultSort: 'serialNumber:ASC' })

  const [drawerOpen, setDrawerOpen] = useState(false)
  const [editTarget, setEditTarget] = useState(null)
  const [form, setForm] = useState(EMPTY_FORM)
  const [submitError, setSubmitError] = useState(null)

  const canWrite = roles.some(r => ['ADMIN', 'MANAGER'].includes(r))

  const { rows, page, isLoading, isError, error, refetch } = usePagedQuery({
    queryKey: ['admin', 'assets', state],
    queryFn: ({ signal }) => listAssets({ ...state, signal }),
  })

  const { getFieldErrors, summaryErrors } = useFieldErrors(submitError, KNOWN_FIELDS)

  const mutation = useMutation({
    mutationFn: (body) => editTarget ? updateAsset(editTarget.id, body) : createAsset(body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin', 'assets'] })
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
    setForm({ serialNumber: row.serialNumber ?? '', assetTag: row.assetTag ?? '',
      description: row.description ?? '', siteId: row.siteId ?? '', assetTypeCode: row.assetTypeCode ?? '' })
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
      <PageHeader title="Assets" actions={canWrite ? <Button onClick={openCreate} variant="primary">Add asset</Button> : null} />

      {rows.length === 0 ? <EmptyState /> : (
        <>
          <DataTable columns={COLUMNS} rows={rows} rowKey={r => r.id}
            onRowClick={canWrite ? openEdit : undefined}
            caption="Assets list" aria-label="Assets" />
          <Pagination page={page} onPageChange={setPage} />
        </>
      )}

      <DetailDrawer open={drawerOpen} onClose={() => setDrawerOpen(false)}
        title={editTarget ? 'Edit asset' : 'Add asset'}>
        <form onSubmit={handleSubmit} noValidate style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-4)' }}>
          <ErrorSummary errors={summaryErrors} traceId={submitError?.traceId} />

          {[
            { field: 'serialNumber', label: 'Serial number', required: true },
            { field: 'assetTag', label: 'Asset tag' },
            { field: 'assetTypeCode', label: 'Asset type code' },
            { field: 'description', label: 'Description' },
            { field: 'siteId', label: 'Site ID (UUID)' },
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
              {mutation.isPending ? 'Saving…' : (editTarget ? 'Save changes' : 'Add asset')}
            </Button>
          </div>
        </form>
      </DetailDrawer>
    </div>
  )
}
