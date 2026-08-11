/**
 * @fileoverview Technician certifications screen.
 *
 * Displays certification currency as returned by the API — never recomputes
 * or caches the `current` field client-side (AC-5).
 *
 * Currency chip shows three states (current/expiring-soon/expired) via
 * icon + text + colour so meaning is never colour-only (BR-34).
 *
 * @module features/admin/certifications/TechnicianCertificationsPage
 */
import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useParams } from 'react-router-dom'
import { useAuth } from '../../../app/AuthContext.js'
import { PageHeader, DataTable, DetailDrawer, FormField, Button } from '../../../components/index.js'
import { LoadingState, ErrorState, EmptyState } from '../../../components/index.js'
import { usePagedQuery } from '../../../shared/hooks/usePagedQuery.js'
import { useFieldErrors, ErrorSummary } from '../../../shared/forms/useFieldErrors.js'
import { CertificationCurrencyChip } from '../components/CertificationCurrencyChip.jsx'
import { listTechnicianCertifications, upsertTechnicianCertifications } from '../../../api/refdata.js'
import { generateAttemptKey } from '../../../lib/idempotency.js'

const KNOWN_FIELDS = ['typeCode', 'certificateReference', 'issuedOn', 'expiresOn', 'issuingBody']

/** @type {import('../../../components/DataTable/DataTable.jsx').ColumnDef[]} */
const COLUMNS = [
  { key: 'typeCode', header: 'Type' },
  { key: 'typeDisplayName', header: 'Name' },
  {
    key: 'regulated',
    header: 'Gate',
    render: v => v ? '🔒 Regulated' : 'Advisory',
  },
  { key: 'issuedOn', header: 'Issued' },
  { key: 'expiresOn', header: 'Expires', render: v => v ?? 'Perpetual' },
  {
    key: 'current',
    header: 'Status',
    render: (v, row) => (
      <CertificationCurrencyChip
        current={row.current}
        daysUntilExpiry={row.daysUntilExpiry}
        expiresOn={row.expiresOn}
      />
    ),
  },
]

const EMPTY_FORM = { typeCode: '', certificateReference: '', issuedOn: '', expiresOn: '', issuingBody: '' }

export default function TechnicianCertificationsPage() {
  const { id: technicianId } = useParams()
  const { roles } = useAuth()
  const qc = useQueryClient()

  const [drawerOpen, setDrawerOpen] = useState(false)
  const [form, setForm] = useState(EMPTY_FORM)
  const [submitError, setSubmitError] = useState(null)
  const [idempotencyKey] = useState(() => generateAttemptKey())

  const canWrite = roles.some(r => ['ADMIN', 'MANAGER'].includes(r))

  const { rows, isLoading, isError, error, refetch } = usePagedQuery({
    queryKey: ['admin', 'technician-certs', technicianId],
    queryFn: ({ signal }) => listTechnicianCertifications(technicianId, { signal }),
    enabled: !!technicianId,
  })

  const { getFieldErrors, summaryErrors } = useFieldErrors(submitError, KNOWN_FIELDS)

  const mutation = useMutation({
    mutationFn: (items) => upsertTechnicianCertifications(
      technicianId,
      { items },
      idempotencyKey
    ),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin', 'technician-certs', technicianId] })
      setDrawerOpen(false)
      setForm(EMPTY_FORM)
      setSubmitError(null)
    },
    onError: (err) => setSubmitError(err),
  })

  function handleSubmit(e) {
    e.preventDefault()
    const item = {
      typeCode: form.typeCode,
      certificateReference: form.certificateReference || undefined,
      issuedOn: form.issuedOn,
      expiresOn: form.expiresOn || undefined,
      issuingBody: form.issuingBody || undefined,
    }
    mutation.mutate([item])
  }

  if (isLoading) return <LoadingState />
  if (isError) return <ErrorState onRetry={refetch} message={error?.message} />

  return (
    <div style={{ padding: 'var(--token-space-6)', maxWidth: '1440px', margin: '0 auto' }}>
      <PageHeader
        title="Certifications"
        description="Currency status is derived from the API — never computed client-side."
        actions={canWrite ? (
          <Button onClick={() => { setDrawerOpen(true); setForm(EMPTY_FORM); setSubmitError(null) }} variant="primary">
            Add certification
          </Button>
        ) : null}
      />

      {rows.length === 0
        ? <EmptyState message="No certifications found for this technician." />
        : (
          <DataTable
            columns={COLUMNS}
            rows={rows}
            rowKey={r => r.id}
            caption="Technician certifications"
            aria-label="Technician certifications"
          />
        )
      }

      <DetailDrawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        title="Add certification"
      >
        <form onSubmit={handleSubmit} noValidate style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-4)' }}>
          <ErrorSummary errors={summaryErrors} traceId={submitError?.traceId} />

          <FormField label="Type code" required errors={getFieldErrors('typeCode')}>
            {(inputProps) => (
              <input
                {...inputProps}
                type="text"
                value={form.typeCode}
                onChange={e => setForm(f => ({ ...f, typeCode: e.target.value }))}
                placeholder="e.g. GAS_SAFE"
                style={{ minHeight: '44px', padding: '0 var(--token-space-3)', border: '1px solid var(--token-border-default)', borderRadius: 'var(--token-radius-control)', width: '100%' }}
              />
            )}
          </FormField>

          <FormField label="Certificate reference" errors={getFieldErrors('certificateReference')}>
            {(inputProps) => (
              <input
                {...inputProps}
                type="text"
                value={form.certificateReference}
                onChange={e => setForm(f => ({ ...f, certificateReference: e.target.value }))}
                style={{ minHeight: '44px', padding: '0 var(--token-space-3)', border: '1px solid var(--token-border-default)', borderRadius: 'var(--token-radius-control)', width: '100%' }}
              />
            )}
          </FormField>

          <FormField label="Issued on" required errors={getFieldErrors('issuedOn')}>
            {(inputProps) => (
              <input
                {...inputProps}
                type="date"
                value={form.issuedOn}
                onChange={e => setForm(f => ({ ...f, issuedOn: e.target.value }))}
                style={{ minHeight: '44px', padding: '0 var(--token-space-3)', border: '1px solid var(--token-border-default)', borderRadius: 'var(--token-radius-control)', width: '100%' }}
              />
            )}
          </FormField>

          <FormField label="Expires on" errors={getFieldErrors('expiresOn')} helpText="Leave blank for perpetual">
            {(inputProps) => (
              <input
                {...inputProps}
                type="date"
                value={form.expiresOn}
                onChange={e => setForm(f => ({ ...f, expiresOn: e.target.value }))}
                style={{ minHeight: '44px', padding: '0 var(--token-space-3)', border: '1px solid var(--token-border-default)', borderRadius: 'var(--token-radius-control)', width: '100%' }}
              />
            )}
          </FormField>

          <FormField label="Issuing body" errors={getFieldErrors('issuingBody')}>
            {(inputProps) => (
              <input
                {...inputProps}
                type="text"
                value={form.issuingBody}
                onChange={e => setForm(f => ({ ...f, issuingBody: e.target.value }))}
                style={{ minHeight: '44px', padding: '0 var(--token-space-3)', border: '1px solid var(--token-border-default)', borderRadius: 'var(--token-radius-control)', width: '100%' }}
              />
            )}
          </FormField>

          <div style={{ display: 'flex', gap: 'var(--token-space-3)', justifyContent: 'flex-end' }}>
            <Button type="button" variant="secondary" onClick={() => setDrawerOpen(false)}>Cancel</Button>
            <Button type="submit" variant="primary" disabled={mutation.isPending}>
              {mutation.isPending ? 'Saving…' : 'Add certification'}
            </Button>
          </div>
        </form>
      </DetailDrawer>
    </div>
  )
}
