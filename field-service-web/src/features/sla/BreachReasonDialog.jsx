/**
 * @fileoverview BreachReasonDialog — inline breach attribution dialog.
 *
 * Fetches the reason-code controlled vocabulary from the server on mount.
 * Submits with an Idempotency-Key (via the authenticated http client).
 * Error handling:
 *   400 → field-level validation errors
 *   403 → access message without existence disclosure
 *   409 → conflict prompt with refresh
 *   429 → retry-after message
 *   5xx → generic retry message
 */

import { useState, useEffect } from 'react'
import { get, post } from '../../api/http.js'

/**
 * @param {{
 *   workOrderId: string,
 *   onClose: () => void,
 *   onSuccess: (reasonCode: string) => void,
 * }} props
 */
export function BreachReasonDialog({ workOrderId, onClose, onSuccess }) {
  const [reasonCodes, setReasonCodes] = useState(/** @type {Array<{code:string,label:string}>} */ ([]))
  const [loading, setLoading] = useState(true)
  const [selectedCode, setSelectedCode] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [fieldError, setFieldError] = useState(/** @type {string|null} */ (null))
  const [serverMessage, setServerMessage] = useState(/** @type {string|null} */ (null))
  const [serverMessageKind, setServerMessageKind] = useState(/** @type {'error'|'warning'|'info'} */ ('error'))

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    get('/sla/breach-reason-codes')
      .then((data) => {
        if (!cancelled) {
          setReasonCodes(Array.isArray(data?.codes) ? data.codes : [])
          setLoading(false)
        }
      })
      .catch(() => {
        if (!cancelled) {
          setReasonCodes([])
          setLoading(false)
        }
      })
    return () => { cancelled = true }
  }, [])

  async function handleSubmit(e) {
    e.preventDefault()
    if (!selectedCode) {
      setFieldError('Please select a reason code.')
      return
    }
    setFieldError(null)
    setServerMessage(null)
    setSubmitting(true)

    try {
      await post(`/work-orders/${workOrderId}/breach-attribution`, { reasonCode: selectedCode })
      onSuccess(selectedCode)
    } catch (err) {
      const status = err?.status ?? err?.statusCode ?? 0
      if (status === 400) {
        const msg = err?.fieldErrors?.[0]?.message ?? err?.message ?? 'Validation error.'
        setFieldError(msg)
      } else if (status === 403) {
        setServerMessage('You do not have permission to record this attribution.')
        setServerMessageKind('error')
      } else if (status === 409) {
        setServerMessage('This work order was updated. Please refresh and try again.')
        setServerMessageKind('warning')
      } else if (status === 429) {
        const retryAfter = err?.retryAfter ?? 60
        setServerMessage(`Too many requests. Please retry after ${retryAfter} seconds.`)
        setServerMessageKind('warning')
      } else {
        setServerMessage('An error occurred. Please try again.')
        setServerMessageKind('error')
      }
    } finally {
      setSubmitting(false)
    }
  }

  const overlayStyle = {
    position: 'fixed',
    inset: 0,
    background: 'rgba(0,0,0,0.4)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 1000,
  }

  const dialogStyle = {
    background: 'var(--token-surface-card)',
    borderRadius: 'var(--token-radius-md)',
    border: '1px solid var(--token-border-default)',
    padding: 'var(--token-space-6)',
    width: '100%',
    maxWidth: 400,
    boxShadow: '0 8px 24px rgba(0,0,0,0.15)',
  }

  const kindColor = {
    error: 'var(--token-danger-emphasis)',
    warning: 'var(--token-warning-emphasis)',
    info: 'var(--token-info-emphasis)',
  }

  return (
    <div role="dialog" aria-modal="true" aria-labelledby="breach-dialog-title" style={overlayStyle}>
      <div style={dialogStyle}>
        <h2
          id="breach-dialog-title"
          style={{
            fontSize: 'var(--token-fs-16)',
            fontWeight: 600,
            color: 'var(--token-text-primary)',
            margin: '0 0 var(--token-space-4)',
          }}
        >
          Record Breach Reason
        </h2>

        {serverMessage && (
          <div
            role="alert"
            style={{
              color: kindColor[serverMessageKind],
              fontSize: 'var(--token-fs-13)',
              marginBottom: 'var(--token-space-3)',
              padding: 'var(--token-space-2) var(--token-space-3)',
              border: `1px solid ${kindColor[serverMessageKind]}`,
              borderRadius: 'var(--token-radius-sm)',
            }}
          >
            {serverMessage}
          </div>
        )}

        <form onSubmit={handleSubmit} noValidate>
          <div style={{ marginBottom: 'var(--token-space-4)' }}>
            <label
              htmlFor="breach-reason-select"
              style={{
                display: 'block',
                fontSize: 'var(--token-fs-13)',
                fontWeight: 500,
                color: 'var(--token-text-primary)',
                marginBottom: 'var(--token-space-1)',
              }}
            >
              Reason code
            </label>
            {loading ? (
              <div
                role="status"
                aria-label="Loading reason codes"
                style={{ fontSize: 'var(--token-fs-13)', color: 'var(--token-text-secondary)' }}
              >
                Loading…
              </div>
            ) : (
              <select
                id="breach-reason-select"
                value={selectedCode}
                onChange={(e) => { setSelectedCode(e.target.value); setFieldError(null) }}
                aria-invalid={!!fieldError}
                aria-describedby={fieldError ? 'breach-reason-error' : undefined}
                disabled={submitting}
                style={{
                  display: 'block',
                  width: '100%',
                  padding: 'var(--token-space-2) var(--token-space-3)',
                  fontSize: 'var(--token-fs-13)',
                  border: fieldError
                    ? '1px solid var(--token-danger-default)'
                    : '1px solid var(--token-border-default)',
                  borderRadius: 'var(--token-radius-sm)',
                  background: 'var(--token-surface-input)',
                  color: 'var(--token-text-primary)',
                  minHeight: 44,
                }}
              >
                <option value="">— Select a reason —</option>
                {reasonCodes.map(({ code, label }) => (
                  <option key={code} value={code}>{label}</option>
                ))}
              </select>
            )}
            {fieldError && (
              <p
                id="breach-reason-error"
                role="alert"
                style={{
                  color: 'var(--token-danger-emphasis)',
                  fontSize: 'var(--token-fs-12)',
                  marginTop: 'var(--token-space-1)',
                }}
              >
                {fieldError}
              </p>
            )}
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 'var(--token-space-2)' }}>
            <button
              type="button"
              onClick={onClose}
              disabled={submitting}
              style={{
                padding: 'var(--token-space-2) var(--token-space-4)',
                fontSize: 'var(--token-fs-13)',
                border: '1px solid var(--token-border-default)',
                borderRadius: 'var(--token-radius-sm)',
                background: 'var(--token-surface-card)',
                cursor: 'pointer',
                color: 'var(--token-text-secondary)',
                minHeight: 44,
              }}
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting || loading}
              aria-busy={submitting}
              style={{
                padding: 'var(--token-space-2) var(--token-space-4)',
                fontSize: 'var(--token-fs-13)',
                border: 'none',
                borderRadius: 'var(--token-radius-sm)',
                background: 'var(--token-accent-600)',
                color: 'var(--token-on-accent)',
                cursor: submitting ? 'wait' : 'pointer',
                fontWeight: 500,
                minHeight: 44,
                opacity: submitting || loading ? 0.7 : 1,
              }}
            >
              {submitting ? 'Saving…' : 'Save'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
