/**
 * @fileoverview Customers admin screen — paginated list, create/edit drawer.
 *
 * SECURITY: Role filtering in the UI is a USABILITY affordance only.
 * Server-side authorization (403) is the actual control.
 *
 * AC-1: ADMIN and MANAGER write; DISPATCHER read-only.
 * AC-3: Server-side pagination via TanStack Query with URL state.
 * AC-4: Server 400 fieldErrors render inline.
 *
 * @module features/admin/customers/CustomersPage
 */
import { useState, useCallback } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../../app/AuthContext.js'
import { PageHeader, DataTable, DetailDrawer, FormField, Button } from '../../../components/index.js'
import { LoadingState, ErrorState, EmptyState } from '../../../components/index.js'
import { usePagedQuery } from '../../../shared/hooks/usePagedQuery.js'
import { useUrlPageState } from '../../../shared/hooks/useUrlPageState.js'
import { useFieldErrors, ErrorSummary } from '../../../shared/forms/useFieldErrors.js'
import { Pagination } from '../../../shared/components/Pagination.jsx'
import { listCustomers, createCustomer, updateCustomer } from '../../../api/refdata.js'

const ALLOWED_SORT = ['name', 'reference', 'createdAt']
const KNOWN_FIELDS = ['name', 'reference']

/** @type {import('../../../components/DataTable/DataTable.jsx').ColumnDef[]} */
const COLUMNS = [
  { key: 'name', header: 'Name', sortable: true },
  { key: 'reference', header: 'Reference', sortable: true },
  { key: 'active', header: 'Status', render: v => v ? 'Active' : 'Inactive' },
]

export default function CustomersPage() {
  const { roles } = useAuth()
  const qc = useQueryClient()
  const { state, setPage, setSort } = useUrlPageState({ defaultSort: 'name:ASC' })

  const [drawerOpen, setDrawerOpen] = useState(false)
  const [editTarget, setEditTarget] = useState(/** @type {Record<string, unknown> | null} */ (null))
  const [form, setForm] = useState({ name: '', reference: '' })
  const [submitError, setSubmitError] = useState(/** @type {import('../../../api/errors.js').ClientError | null} */ (null))

  const canWrite = roles.some(r => ['ADMIN', 'MANAGER'].includes(r))

  const { rows, page, isLoading, isError, error, refetch } = usePagedQuery({
    queryKey: ['admin', 'customers', state],
    queryFn: ({ signal }) => listCustomers({ ...state, signal }),
  })

  const { getFieldErrors, summaryErrors } = useFieldErrors(submitError, KNOWN_FIELDS)

  const mutation = useMutation({
    mutationFn: (body) => editTarget
      ? updateCustomer(editTarget.id, body)
      : createCustomer(body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin', 'customers'] })
      setDrawerOpen(false)
      setEditTarget(null)
      setForm({ name: '', reference: '' })
      setSubmitError(null)
    },
    onError: (err) => setSubmitError(err),
  })

  function openCreate() {
    setEditTarget(null)
    setForm({ name: '', reference: '' })
    setSubmitError(null)
    setDrawerOpen(true)
  }

  function openEdit(row) {
    setEditTarget(row)
    setForm({ name: row.name ?? '', reference: row.reference ?? '' })
    setSubmitError(null)
    setDrawerOpen(true)
  }

  function handleSortChange(field) {
    if (!ALLOWED_SORT.includes(field)) return
    setSort(state.sort?.startsWith(field)
      ? (state.sort === `${field}:ASC` ? `${field}:DESC` : undefined)
      : `${field}:ASC`)
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
        title="Customers"
        actions={canWrite ? (
          <Button onClick={openCreate} variant="primary">Add customer</Button>
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
              caption="Customers list"
              aria-label="Customers"
            />
            <Pagination page={page} onPageChange={setPage} />
          </>
        )
      }

      <DetailDrawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        title={editTarget ? 'Edit customer' : 'Add customer'}
      >
        <form onSubmit={handleSubmit} noValidate style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-4)' }}>
          <ErrorSummary errors={summaryErrors} traceId={submitError?.traceId} />

          <FormField label="Name" required errors={getFieldErrors('name')}>
            {(inputProps) => (
              <input
                {...inputProps}
                type="text"
                value={form.name}
                onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                style={{ minHeight: '44px', padding: '0 var(--token-space-3)', border: '1px solid var(--token-border-default)', borderRadius: 'var(--token-radius-control)', width: '100%' }}
              />
            )}
          </FormField>

          <FormField label="Reference" errors={getFieldErrors('reference')}>
            {(inputProps) => (
              <input
                {...inputProps}
                type="text"
                value={form.reference}
                onChange={e => setForm(f => ({ ...f, reference: e.target.value }))}
                style={{ minHeight: '44px', padding: '0 var(--token-space-3)', border: '1px solid var(--token-border-default)', borderRadius: 'var(--token-radius-control)', width: '100%' }}
              />
            )}
          </FormField>

          <div style={{ display: 'flex', gap: 'var(--token-space-3)', justifyContent: 'flex-end' }}>
            <Button type="button" variant="secondary" onClick={() => setDrawerOpen(false)}>Cancel</Button>
            <Button type="submit" variant="primary" disabled={mutation.isPending}>
              {mutation.isPending ? 'Saving…' : (editTarget ? 'Save changes' : 'Add customer')}
            </Button>
          </div>
        </form>
      </DetailDrawer>
    </div>
  )
}
