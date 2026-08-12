/**
 * @fileoverview Destructive erasure confirmation dialog.
 *
 * AC-7: Requires explicit "CONFIRM_ERASURE" typed input before enabling submit.
 * AC-7: States irreversibility and what non-identifying records are retained.
 * AC-7: Saturated danger colour is reserved for this confirmation only.
 * AC-9: Idempotency key attached by the HTTP layer — double-click is safe.
 *
 * @module features/admin/privacy/ErasureConfirmDialog
 */
import { useState, useCallback } from 'react'
import { Modal, FormField, Button } from '../../../components/index.js'

const REQUIRED_CONFIRMATION = 'CONFIRM_ERASURE'

/**
 * @param {{
 *   open: boolean,
 *   onClose: () => void,
 *   subjectType: string,
 *   subjectId: string,
 *   dsarRequestId: string,
 *   onConfirm: (body: { dsarRequestId: string, confirmation: string, note: string | null }) => void,
 *   isPending: boolean,
 *   errorMessage: string | null
 * }} props
 */
export function ErasureConfirmDialog({
  open,
  onClose,
  subjectType,
  subjectId,
  dsarRequestId,
  onConfirm,
  isPending,
  errorMessage,
}) {
  const [confirmText, setConfirmText] = useState('')
  const [note, setNote] = useState('')

  const isConfirmValid = confirmText === REQUIRED_CONFIRMATION
  const canSubmit = isConfirmValid && !isPending

  const handleClose = useCallback(() => {
    setConfirmText('')
    setNote('')
    onClose()
  }, [onClose])

  function handleSubmit(e) {
    e.preventDefault()
    if (!canSubmit) return
    onConfirm({
      dsarRequestId,
      confirmation: REQUIRED_CONFIRMATION,
      note: note || null,
    })
  }

  if (!open) return null

  return (
    <Modal open={open} onClose={handleClose} title="Confirm Cryptographic Erasure" size="md">
      <form onSubmit={handleSubmit} noValidate style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-4)' }}>
        {/* Danger notice — saturated danger colour per AC-7/BR-31 */}
        <div
          role="alert"
          style={{
            background: 'var(--token-danger-subtle)',
            border: '2px solid var(--token-danger-default)',
            borderRadius: 'var(--token-radius-control)',
            padding: 'var(--token-space-4)',
            color: 'var(--token-danger-emphasis)',
            fontFamily: 'var(--token-family-base)',
            fontSize: 'var(--token-fs-14)',
          }}
        >
          <p style={{ margin: '0 0 var(--token-space-2)', fontWeight: 700, fontSize: 'var(--token-fs-16)' }}>
            ⚠ This action is irreversible
          </p>
          <p style={{ margin: '0 0 var(--token-space-2)' }}>
            Confirming erasure will destroy the encryption key for{' '}
            <strong>{subjectType}</strong> {subjectId}. Every copy of the subject&apos;s
            personal data — including live rows, audit history, backups, caches, and
            prior exports — will become permanently unreadable.
          </p>
          <p style={{ margin: 0 }}>
            <strong>What is retained:</strong> Non-identifying transaction records
            (work order counts, closure timestamps, SLA compliance figures, audit
            revision numbers) remain intact and queryable.
          </p>
        </div>

        <dl style={{ margin: 0, fontSize: 'var(--token-fs-14)', display: 'grid', gridTemplateColumns: 'max-content 1fr', gap: 'var(--token-space-1) var(--token-space-4)' }}>
          <dt style={{ color: 'var(--token-text-secondary)' }}>Subject type</dt>
          <dd style={{ margin: 0 }}>{subjectType}</dd>
          <dt style={{ color: 'var(--token-text-secondary)' }}>Subject ID</dt>
          <dd style={{ margin: 0, fontVariantNumeric: 'tabular-nums' }}>{subjectId}</dd>
          <dt style={{ color: 'var(--token-text-secondary)' }}>Authorising DSAR</dt>
          <dd style={{ margin: 0, fontVariantNumeric: 'tabular-nums' }}>{dsarRequestId}</dd>
        </dl>

        {errorMessage && (
          <div
            role="alert"
            aria-live="assertive"
            style={{
              background: 'var(--token-danger-subtle)',
              border: '1px solid var(--token-danger-default)',
              borderRadius: 'var(--token-radius-control)',
              padding: 'var(--token-space-3)',
              fontSize: 'var(--token-fs-14)',
              color: 'var(--token-danger-emphasis)',
            }}
          >
            {errorMessage}
          </div>
        )}

        <FormField
          label={`Type "${REQUIRED_CONFIRMATION}" to proceed`}
          required
        >
          {(inputProps) => (
            <input
              {...inputProps}
              type="text"
              autoComplete="off"
              value={confirmText}
              onChange={e => setConfirmText(e.target.value)}
              aria-describedby="confirm-hint"
              style={{
                minHeight: '44px',
                padding: '0 var(--token-space-3)',
                border: `1px solid ${isConfirmValid ? 'var(--token-success-default)' : 'var(--token-border-default)'}`,
                borderRadius: 'var(--token-radius-control)',
                width: '100%',
                fontSize: 'var(--token-fs-14)',
                fontFamily: 'var(--token-family-base)',
              }}
            />
          )}
        </FormField>
        <p
          id="confirm-hint"
          style={{ margin: '-var(--token-space-2) 0 0', fontSize: 'var(--token-fs-13)', color: 'var(--token-text-secondary)' }}
        >
          The confirm button is disabled until the exact phrase is entered.
        </p>

        <FormField label="Optional note">
          {(inputProps) => (
            <textarea
              {...inputProps}
              value={note}
              onChange={e => setNote(e.target.value)}
              rows={2}
              maxLength={1000}
              style={{
                padding: 'var(--token-space-2) var(--token-space-3)',
                border: '1px solid var(--token-border-default)',
                borderRadius: 'var(--token-radius-control)',
                width: '100%',
                resize: 'vertical',
                fontSize: 'var(--token-fs-14)',
                fontFamily: 'var(--token-family-base)',
              }}
            />
          )}
        </FormField>

        <div style={{ display: 'flex', gap: 'var(--token-space-3)', justifyContent: 'flex-end' }}>
          <Button type="button" variant="secondary" onClick={handleClose} disabled={isPending}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant="primary"
            disabled={!canSubmit}
            aria-disabled={!canSubmit}
            style={{
              background: canSubmit ? 'var(--token-danger-default)' : undefined,
              borderColor: canSubmit ? 'var(--token-danger-default)' : undefined,
            }}
          >
            {isPending ? 'Erasing…' : 'Confirm erasure — irreversible'}
          </Button>
        </div>
      </form>
    </Modal>
  )
}
