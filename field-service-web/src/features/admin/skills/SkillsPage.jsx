/**
 * @fileoverview Skills admin screen — paginated list, create/edit drawer.
 * @module features/admin/skills/SkillsPage
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
import { listSkills, createSkill, updateSkill } from '../../../api/refdata.js'

const KNOWN_FIELDS = ['code', 'displayName', 'description', 'active']

/** @type {import('../../../components/DataTable/DataTable.jsx').ColumnDef[]} */
const COLUMNS = [
  { key: 'code', header: 'Code', sortable: true },
  { key: 'displayName', header: 'Name' },
  { key: 'description', header: 'Description' },
  { key: 'active', header: 'Status', render: v => v ? 'Active' : 'Inactive' },
]

const EMPTY_FORM = { code: '', displayName: '', description: '', active: true }

export default function SkillsPage() {
  const { roles } = useAuth()
  const qc = useQueryClient()
  const { state, setPage } = useUrlPageState({ defaultSort: 'code:ASC' })

  const [drawerOpen, setDrawerOpen] = useState(false)
  const [editTarget, setEditTarget] = useState(null)
  const [form, setForm] = useState(EMPTY_FORM)
  const [submitError, setSubmitError] = useState(null)

  const canWrite = roles.some(r => ['ADMIN', 'MANAGER'].includes(r))

  const { rows, page, isLoading, isError, error, refetch } = usePagedQuery({
    queryKey: ['admin', 'skills', state],
    queryFn: ({ signal }) => listSkills({ ...state, signal }),
  })

  const { getFieldErrors, summaryErrors } = useFieldErrors(submitError, KNOWN_FIELDS)

  const mutation = useMutation({
    mutationFn: (body) => editTarget ? updateSkill(editTarget.id, body) : createSkill(body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin', 'skills'] })
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
    setForm({ code: row.code ?? '', displayName: row.displayName ?? '',
      description: row.description ?? '', active: row.active ?? true })
    setSubmitError(null); setDrawerOpen(true)
  }

  function handleSubmit(e) {
    e.preventDefault()
    mutation.mutate({ ...form, active: form.active })
  }

  if (isLoading) return <LoadingState />
  if (isError) return <ErrorState onRetry={refetch} message={error?.message} />

  return (
    <div style={{ padding: 'var(--token-space-6)', maxWidth: '1440px', margin: '0 auto' }}>
      <PageHeader title="Skills" actions={canWrite ? <Button onClick={openCreate} variant="primary">Add skill</Button> : null} />

      {rows.length === 0 ? <EmptyState /> : (
        <>
          <DataTable columns={COLUMNS} rows={rows} rowKey={r => r.id}
            onRowClick={canWrite ? openEdit : undefined}
            caption="Skills list" aria-label="Skills" />
          <Pagination page={page} onPageChange={setPage} />
        </>
      )}

      <DetailDrawer open={drawerOpen} onClose={() => setDrawerOpen(false)}
        title={editTarget ? 'Edit skill' : 'Add skill'}>
        <form onSubmit={handleSubmit} noValidate style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-4)' }}>
          <ErrorSummary errors={summaryErrors} traceId={submitError?.traceId} />

          <FormField label="Code" required errors={getFieldErrors('code')} helpText="UPPER_SNAKE_CASE">
            {(inputProps) => (
              <input {...inputProps} type="text" value={form.code} disabled={!!editTarget}
                onChange={e => setForm(f => ({ ...f, code: e.target.value }))}
                style={{ minHeight: '44px', padding: '0 var(--token-space-3)', border: '1px solid var(--token-border-default)', borderRadius: 'var(--token-radius-control)', width: '100%' }} />
            )}
          </FormField>

          <FormField label="Display name" required errors={getFieldErrors('displayName')}>
            {(inputProps) => (
              <input {...inputProps} type="text" value={form.displayName}
                onChange={e => setForm(f => ({ ...f, displayName: e.target.value }))}
                style={{ minHeight: '44px', padding: '0 var(--token-space-3)', border: '1px solid var(--token-border-default)', borderRadius: 'var(--token-radius-control)', width: '100%' }} />
            )}
          </FormField>

          <FormField label="Description" errors={getFieldErrors('description')}>
            {(inputProps) => (
              <textarea {...inputProps} value={form.description}
                onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
                rows={3}
                style={{ padding: 'var(--token-space-2) var(--token-space-3)', border: '1px solid var(--token-border-default)', borderRadius: 'var(--token-radius-control)', width: '100%', fontFamily: 'inherit' }} />
            )}
          </FormField>

          {editTarget && (
            <FormField label="Active" errors={getFieldErrors('active')}>
              {(inputProps) => (
                <label style={{ display: 'flex', alignItems: 'center', gap: 'var(--token-space-2)', minHeight: '44px', cursor: 'pointer' }}>
                  <input {...inputProps} type="checkbox" checked={form.active}
                    onChange={e => setForm(f => ({ ...f, active: e.target.checked }))}
                    style={{ width: '18px', height: '18px' }} />
                  Active
                </label>
              )}
            </FormField>
          )}

          <div style={{ display: 'flex', gap: 'var(--token-space-3)', justifyContent: 'flex-end' }}>
            <Button type="button" variant="secondary" onClick={() => setDrawerOpen(false)}>Cancel</Button>
            <Button type="submit" variant="primary" disabled={mutation.isPending}>
              {mutation.isPending ? 'Saving…' : (editTarget ? 'Save changes' : 'Add skill')}
            </Button>
          </div>
        </form>
      </DetailDrawer>
    </div>
  )
}
