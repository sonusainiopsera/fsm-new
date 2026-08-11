/**
 * @fileoverview PartsLoggingPanel — technician parts logging and return flow.
 *
 * AC-5: Part search by number or description, numeric quantity entry (inputMode=numeric),
 * reason-code selection, multi-line staging, single atomic submit.
 * Optimised for 360 px one-handed use with ≥44 px targets, no horizontal scroll.
 *
 * AC-6: One idempotency key per submission attempt, reused verbatim on retry,
 * regenerated only on new submission.
 *
 * AC-7: 422 INSUFFICIENT_STOCK renders per-line requested-versus-available
 * detail and offers a one-tap awaiting-parts hold action.
 *
 * AC-8: Network failure shows an explicit not-connected retry state.
 * Returns flow reuses same validation, idempotency, and error behaviour.
 *
 * AC-10: All interactive targets ≥44 px; labelled inputs; inputMode=numeric;
 * focus management; reduced-motion honoured; no hover-only affordances.
 *
 * NOTE: Role-based hiding (AC-9) — this panel is rendered only for TECHNICIAN
 * role in the surface layer. That is a USABILITY affordance only. The server
 * enforces authorization for all writes.
 */
import { useState, useCallback, useRef } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../../app/AuthContext.js'
import { FormField } from '../../../components/index.js'
import { LoadingState, ErrorState, EmptyState } from '../../../components/index.js'
import { consumeParts, returnParts, searchParts, placeOnAwaitingPartsHold } from '../../../api/inventory.js'
import { generateAttemptKey } from '../../../lib/idempotency.js'

const REASON_CODES = ['INSTALLATION', 'REPAIR', 'REPLACEMENT', 'MAINTENANCE', 'OTHER']

/**
 * @typedef {{ partId: string, partNumber: string, description: string, unitOfMeasure: string }} PartResult
 * @typedef {{ partId: string, partNumber: string, description: string, quantity: number, reasonCode: string, unitOfMeasure: string }} StagedLine
 */

/**
 * @param {{
 *   workOrderId: string,
 *   workOrderVersion?: number,
 *   mode?: 'consume' | 'return',
 *   onSuccess?: () => void
 * }} props
 */
export function PartsLoggingPanel({ workOrderId, workOrderVersion, mode = 'consume', onSuccess }) {
  const queryClient = useQueryClient()

  // ── Search state ──────────────────────────────────────────────────────────
  const [searchQuery, setSearchQuery] = useState('')
  const [searchResults, setSearchResults] = useState(/** @type {PartResult[]} */ ([]))
  const [isSearching, setIsSearching] = useState(false)
  const [searchError, setSearchError] = useState(/** @type {string | null} */ (null))
  const searchAbortRef = useRef(/** @type {AbortController | null} */ (null))
  const searchTimerRef = useRef(/** @type {ReturnType<typeof setTimeout> | null} */ (null))

  // ── Staged lines ──────────────────────────────────────────────────────────
  const [stagedLines, setStagedLines] = useState(/** @type {StagedLine[]} */ ([]))

  // ── Per-line quantity/reason state ────────────────────────────────────────
  const [pendingPart, setPendingPart] = useState(/** @type {PartResult | null} */ (null))
  const [pendingQty, setPendingQty] = useState('')
  const [pendingReason, setPendingReason] = useState(REASON_CODES[0])
  const [qtyError, setQtyError] = useState(/** @type {string | null} */ (null))

  // ── Idempotency — one key per submission attempt (AC-6) ───────────────────
  const [attemptKey, setAttemptKey] = useState(() => generateAttemptKey())

  // ── Submission state ──────────────────────────────────────────────────────
  const [fieldErrors, setFieldErrors] = useState(/** @type {import('../../../api/errors.js').FieldError[]} */ ([]))
  const [insufficientStock, setInsufficientStock] = useState(false)
  const [isNetworkError, setIsNetworkError] = useState(false)
  const [isSubmitting, setIsSubmitting] = useState(false)

  // ── Debounced part search ─────────────────────────────────────────────────
  const runSearch = useCallback((query) => {
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current)
    if (searchAbortRef.current) searchAbortRef.current.abort()

    if (!query || query.trim().length < 2) {
      setSearchResults([])
      setSearchError(null)
      return
    }

    searchTimerRef.current = setTimeout(async () => {
      const controller = new AbortController()
      searchAbortRef.current = controller
      setIsSearching(true)
      setSearchError(null)
      try {
        const result = await searchParts({ query: query.trim(), signal: controller.signal })
        setSearchResults(result?.data ?? [])
      } catch (err) {
        if (err?.name === 'AbortError') return
        setSearchError('Could not search parts. Check your connection.')
        setSearchResults([])
      } finally {
        setIsSearching(false)
      }
    }, 300)
  }, [])

  function handleSearchChange(e) {
    const q = e.target.value
    setSearchQuery(q)
    runSearch(q)
  }

  // ── Add pending part to staged lines ─────────────────────────────────────
  function handleAddLine() {
    if (!pendingPart) return
    const qty = parseInt(pendingQty, 10)
    if (!pendingQty || isNaN(qty) || qty <= 0) {
      setQtyError('Quantity must be a whole number greater than 0.')
      return
    }
    setQtyError(null)
    setStagedLines(prev => [
      ...prev,
      {
        partId: pendingPart.partId,
        partNumber: pendingPart.partNumber,
        description: pendingPart.description,
        quantity: qty,
        reasonCode: pendingReason,
        unitOfMeasure: pendingPart.unitOfMeasure,
      },
    ])
    setPendingPart(null)
    setPendingQty('')
    setSearchQuery('')
    setSearchResults([])
    setPendingReason(REASON_CODES[0])
  }

  function handleRemoveLine(idx) {
    setStagedLines(prev => prev.filter((_, i) => i !== idx))
  }

  // ── Submit ────────────────────────────────────────────────────────────────
  async function handleSubmit(e) {
    e.preventDefault()
    if (stagedLines.length === 0) return
    if (isSubmitting) return // prevent rapid double-tap

    setFieldErrors([])
    setInsufficientStock(false)
    setIsNetworkError(false)
    setIsSubmitting(true)

    const command = {
      workOrderId,
      lines: stagedLines.map(l => ({
        partId: l.partId,
        quantity: l.quantity,
        reasonCode: l.reasonCode,
      })),
    }

    const options = {
      headers: { 'Idempotency-Key': attemptKey },
    }

    try {
      const fn = mode === 'return' ? returnParts : consumeParts
      await fn(command, options)
      // Success: invalidate affected queries
      await queryClient.invalidateQueries({ queryKey: ['work-orders', workOrderId, 'parts'] })
      await queryClient.invalidateQueries({ queryKey: ['inventory', 'stock'] })
      await queryClient.invalidateQueries({ queryKey: ['inventory', 'movements'] })
      // Advance to a fresh key for the next submission
      setAttemptKey(generateAttemptKey())
      setStagedLines([])
      onSuccess?.()
    } catch (err) {
      if (err?.status === 0) {
        // Network error — explicit not-connected state (AC-8); never show optimistic success
        setIsNetworkError(true)
      } else if (err?.status === 422 && err?.code === 'INSUFFICIENT_STOCK') {
        setInsufficientStock(true)
        setFieldErrors(err?.fieldErrors ?? [])
      } else if (err?.status === 400 && Array.isArray(err?.fieldErrors)) {
        setFieldErrors(err.fieldErrors)
      }
      // Do NOT advance the key — same key must be reused on retry (AC-6)
    } finally {
      setIsSubmitting(false)
    }
  }

  // ── Awaiting parts hold action (AC-7) ─────────────────────────────────────
  const [isPlacingHold, setIsPlacingHold] = useState(false)
  const [holdError, setHoldError] = useState(/** @type {string | null} */ (null))

  async function handlePlaceHold() {
    if (workOrderVersion == null) return
    setIsPlacingHold(true)
    setHoldError(null)
    try {
      await placeOnAwaitingPartsHold({ workOrderId, expectedVersion: workOrderVersion })
      await queryClient.invalidateQueries({ queryKey: ['work-orders', workOrderId] })
      setInsufficientStock(false)
      setFieldErrors([])
    } catch (err) {
      setHoldError(err?.message ?? 'Could not place hold. Please try again.')
    } finally {
      setIsPlacingHold(false)
    }
  }

  // ── Retry after network error ─────────────────────────────────────────────
  function handleRetry() {
    setIsNetworkError(false)
    // same attemptKey is kept — reused on retry (AC-6)
  }

  // ── Render ────────────────────────────────────────────────────────────────
  const isReturn = mode === 'return'
  const actionLabel = isReturn ? 'Return parts' : 'Log parts'

  return (
    <section
      aria-label={isReturn ? 'Return parts panel' : 'Log parts panel'}
      style={{
        fontFamily: 'var(--token-family-base)',
        fontSize: 'var(--token-fs-14)',
        maxWidth: '100%',
        padding: 'var(--token-space-4)',
      }}
    >
      <h2 style={{ fontSize: 'var(--token-fs-16)', fontWeight: 600, color: 'var(--token-text-primary)', margin: '0 0 var(--token-space-4)' }}>
        {actionLabel}
      </h2>

      {/* Not-connected retry state (AC-8) — never shows optimistic success */}
      {isNetworkError && (
        <div
          role="alert"
          aria-live="assertive"
          style={{ padding: 'var(--token-space-4)', border: '1px solid var(--token-danger-default)', borderRadius: 'var(--token-radius-card)', marginBottom: 'var(--token-space-4)', background: 'var(--token-surface-card)' }}
        >
          <p style={{ margin: '0 0 var(--token-space-3)', color: 'var(--token-danger-default)', fontWeight: 600 }}>
            ✕ Not connected
          </p>
          <p style={{ margin: '0 0 var(--token-space-4)', color: 'var(--token-text-primary)' }}>
            Your parts log could not be submitted. No lines were recorded. Check your connection and try again.
          </p>
          <button
            type="button"
            onClick={handleRetry}
            style={{ padding: 'var(--token-space-3) var(--token-space-4)', borderRadius: 'var(--token-radius-control)', border: '1px solid var(--token-border-default)', background: 'var(--token-surface-card)', color: 'var(--token-text-primary)', cursor: 'pointer', minHeight: '44px', fontFamily: 'var(--token-family-base)', fontSize: 'var(--token-fs-14)' }}
          >
            Retry submission
          </button>
        </div>
      )}

      {/* 422 Insufficient stock (AC-7) */}
      {insufficientStock && !isNetworkError && (
        <div
          role="alert"
          aria-live="assertive"
          style={{ padding: 'var(--token-space-4)', border: '1px solid var(--token-warning-default)', borderRadius: 'var(--token-radius-card)', marginBottom: 'var(--token-space-4)', background: 'var(--token-surface-card)' }}
        >
          <p style={{ margin: '0 0 var(--token-space-3)', color: 'var(--token-warning-default)', fontWeight: 600 }}>
            ⚠ Insufficient stock — no lines were applied
          </p>
          {fieldErrors.length > 0 && (
            <ul style={{ margin: '0 0 var(--token-space-4)', padding: '0 0 0 var(--token-space-4)', color: 'var(--token-text-primary)' }}>
              {fieldErrors.map((fe, i) => (
                <li key={i} style={{ marginBottom: 'var(--token-space-1)' }}>
                  <strong>{fe.field}:</strong> {fe.message}
                </li>
              ))}
            </ul>
          )}
          <p style={{ margin: '0 0 var(--token-space-4)', color: 'var(--token-text-secondary)', fontSize: 'var(--token-fs-13)' }}>
            You can place this work order on hold while parts are sourced.
          </p>
          <button
            type="button"
            onClick={handlePlaceHold}
            disabled={isPlacingHold || workOrderVersion == null}
            style={{
              padding: 'var(--token-space-3) var(--token-space-4)',
              borderRadius: 'var(--token-radius-control)',
              border: '1px solid var(--token-border-default)',
              background: 'var(--token-accent-50)',
              color: 'var(--token-accent-700)',
              cursor: isPlacingHold || workOrderVersion == null ? 'not-allowed' : 'pointer',
              minHeight: '44px',
              fontFamily: 'var(--token-family-base)',
              fontSize: 'var(--token-fs-14)',
              fontWeight: 600,
            }}
          >
            {isPlacingHold ? 'Placing hold…' : 'Place on hold — Awaiting Parts'}
          </button>
          {holdError && (
            <p role="alert" style={{ margin: 'var(--token-space-2) 0 0', color: 'var(--token-danger-default)', fontSize: 'var(--token-fs-13)' }}>
              {holdError}
            </p>
          )}
        </div>
      )}

      {/* Validation field errors (400) */}
      {!insufficientStock && fieldErrors.length > 0 && (
        <ul role="alert" aria-live="assertive" style={{ padding: '0 0 0 var(--token-space-4)', marginBottom: 'var(--token-space-4)', color: 'var(--token-danger-default)' }}>
          {fieldErrors.map((fe, i) => <li key={i}>{fe.field}: {fe.message}</li>)}
        </ul>
      )}

      {/* Part search */}
      <div style={{ marginBottom: 'var(--token-space-4)' }}>
        <FormField
          label="Search parts"
          htmlFor="part-search"
          hint="Search by part number or description (min. 2 characters)"
        >
          <input
            id="part-search"
            type="search"
            role="combobox"
            aria-expanded={searchResults.length > 0}
            aria-autocomplete="list"
            aria-controls="part-search-results"
            value={searchQuery}
            onChange={handleSearchChange}
            placeholder="e.g. HVAC-FILTER or air filter"
            autoComplete="off"
            style={{
              width: '100%',
              padding: 'var(--token-space-3)',
              borderRadius: 'var(--token-radius-control)',
              border: '1px solid var(--token-border-default)',
              fontSize: 'var(--token-fs-14)',
              fontFamily: 'var(--token-family-base)',
              background: 'var(--token-surface-card)',
              color: 'var(--token-text-primary)',
              minHeight: '44px',
              boxSizing: 'border-box',
            }}
          />
        </FormField>

        {isSearching && <p style={{ fontSize: 'var(--token-fs-13)', color: 'var(--token-text-secondary)', margin: 'var(--token-space-2) 0 0' }}>Searching…</p>}
        {searchError && <p role="alert" style={{ fontSize: 'var(--token-fs-13)', color: 'var(--token-danger-default)', margin: 'var(--token-space-2) 0 0' }}>{searchError}</p>}

        {/* Search results dropdown */}
        {searchResults.length > 0 && (
          <ul
            id="part-search-results"
            role="listbox"
            aria-label="Part search results"
            style={{
              listStyle: 'none',
              margin: 'var(--token-space-1) 0 0',
              padding: 0,
              border: '1px solid var(--token-border-default)',
              borderRadius: 'var(--token-radius-control)',
              background: 'var(--token-surface-overlay)',
              maxHeight: '200px',
              overflowY: 'auto',
            }}
          >
            {searchResults.map(part => (
              <li
                key={part.partId}
                role="option"
                aria-selected={pendingPart?.partId === part.partId}
                onClick={() => { setPendingPart(part); setSearchQuery(part.partNumber); setSearchResults([]) }}
                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setPendingPart(part); setSearchQuery(part.partNumber); setSearchResults([]) } }}
                tabIndex={0}
                style={{
                  padding: 'var(--token-space-3) var(--token-space-4)',
                  cursor: 'pointer',
                  borderBottom: 'var(--token-elevation-border)',
                  minHeight: '44px',
                  display: 'flex',
                  flexDirection: 'column',
                  justifyContent: 'center',
                  background: pendingPart?.partId === part.partId ? 'var(--token-accent-50)' : undefined,
                }}
              >
                <span style={{ fontWeight: 600, color: 'var(--token-text-primary)' }}>{part.partNumber}</span>
                {/* Long descriptions truncate with accessible tooltip */}
                <span
                  style={{ fontSize: 'var(--token-fs-12)', color: 'var(--token-text-secondary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: '100%' }}
                  title={part.description}
                >
                  {part.description}
                </span>
                <span style={{ fontSize: 'var(--token-fs-12)', color: 'var(--token-text-secondary)' }}>{part.unitOfMeasure}</span>
              </li>
            ))}
          </ul>
        )}

        {/* No results empty state */}
        {searchQuery.trim().length >= 2 && !isSearching && searchResults.length === 0 && !searchError && (
          <p style={{ fontSize: 'var(--token-fs-13)', color: 'var(--token-text-secondary)', margin: 'var(--token-space-2) 0 0' }}>
            No parts found for "{searchQuery}". Try a different search term.
          </p>
        )}
      </div>

      {/* Quantity + reason code (only shown when a part is selected) */}
      {pendingPart && (
        <div style={{ marginBottom: 'var(--token-space-4)', padding: 'var(--token-space-3)', border: '1px solid var(--token-border-default)', borderRadius: 'var(--token-radius-card)', background: 'var(--token-surface-card)' }}>
          <p style={{ margin: '0 0 var(--token-space-3)', fontWeight: 600, color: 'var(--token-text-primary)' }}>
            {pendingPart.partNumber}
            <span style={{ fontWeight: 400, color: 'var(--token-text-secondary)', marginLeft: 'var(--token-space-2)', fontSize: 'var(--token-fs-13)' }}>{pendingPart.description}</span>
          </p>

          <div style={{ display: 'flex', gap: 'var(--token-space-3)', flexWrap: 'wrap' }}>
            <FormField
              label="Quantity"
              htmlFor="pending-qty"
              error={qtyError}
              style={{ flex: '0 0 80px' }}
            >
              <input
                id="pending-qty"
                type="text"
                inputMode="numeric"
                pattern="[0-9]*"
                aria-label={`Quantity of ${pendingPart.partNumber}`}
                value={pendingQty}
                onChange={e => { setPendingQty(e.target.value); setQtyError(null) }}
                placeholder="0"
                style={{
                  width: '80px',
                  padding: 'var(--token-space-3)',
                  borderRadius: 'var(--token-radius-control)',
                  border: qtyError ? '1px solid var(--token-danger-default)' : '1px solid var(--token-border-default)',
                  fontSize: 'var(--token-fs-14)',
                  fontFamily: 'var(--token-family-base)',
                  background: 'var(--token-surface-card)',
                  color: 'var(--token-text-primary)',
                  minHeight: '44px',
                  textAlign: 'right',
                  fontVariantNumeric: 'var(--token-numeric)',
                }}
              />
            </FormField>

            <FormField
              label="Reason code"
              htmlFor="pending-reason"
              style={{ flex: '1 1 160px' }}
            >
              <select
                id="pending-reason"
                aria-label="Reason for parts usage"
                value={pendingReason}
                onChange={e => setPendingReason(e.target.value)}
                style={{
                  width: '100%',
                  padding: 'var(--token-space-3)',
                  borderRadius: 'var(--token-radius-control)',
                  border: '1px solid var(--token-border-default)',
                  fontSize: 'var(--token-fs-14)',
                  fontFamily: 'var(--token-family-base)',
                  background: 'var(--token-surface-card)',
                  color: 'var(--token-text-primary)',
                  minHeight: '44px',
                }}
              >
                {REASON_CODES.map(rc => <option key={rc} value={rc}>{rc.replace(/_/g, ' ')}</option>)}
              </select>
            </FormField>
          </div>

          <button
            type="button"
            onClick={handleAddLine}
            style={{
              marginTop: 'var(--token-space-3)',
              width: '100%',
              padding: 'var(--token-space-3)',
              borderRadius: 'var(--token-radius-control)',
              border: '1px solid var(--token-border-default)',
              background: 'var(--token-accent-50)',
              color: 'var(--token-accent-700)',
              cursor: 'pointer',
              fontFamily: 'var(--token-family-base)',
              fontSize: 'var(--token-fs-14)',
              fontWeight: 600,
              minHeight: '44px',
            }}
          >
            + Add to list
          </button>
        </div>
      )}

      {/* Staged lines list */}
      {stagedLines.length > 0 && (
        <div style={{ marginBottom: 'var(--token-space-4)' }}>
          <h3 style={{ fontSize: 'var(--token-fs-13)', color: 'var(--token-text-secondary)', fontWeight: 600, margin: '0 0 var(--token-space-2)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
            Staged lines ({stagedLines.length})
          </h3>
          <ul aria-label="Staged parts lines" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
            {stagedLines.map((line, idx) => (
              <li
                key={idx}
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  padding: 'var(--token-space-3)',
                  border: 'var(--token-elevation-border)',
                  borderRadius: 'var(--token-radius-control)',
                  marginBottom: 'var(--token-space-2)',
                  background: 'var(--token-surface-card)',
                  gap: 'var(--token-space-3)',
                }}
              >
                <div style={{ flex: 1, minWidth: 0 }}>
                  <span style={{ fontWeight: 600, color: 'var(--token-text-primary)', display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {line.partNumber}
                  </span>
                  <span style={{ fontSize: 'var(--token-fs-12)', color: 'var(--token-text-secondary)' }}>
                    Qty: <strong style={{ fontVariantNumeric: 'var(--token-numeric)' }}>{line.quantity}</strong> {line.unitOfMeasure} · {line.reasonCode.replace(/_/g, ' ')}
                  </span>
                  {/* Per-line field error from 422 (AC-7) */}
                  {fieldErrors.find(fe => fe.field === `lines[${idx}].quantity`) && (
                    <span role="alert" style={{ display: 'block', fontSize: 'var(--token-fs-12)', color: 'var(--token-danger-default)', marginTop: 'var(--token-space-1)' }}>
                      {fieldErrors.find(fe => fe.field === `lines[${idx}].quantity`)?.message}
                    </span>
                  )}
                </div>
                <button
                  type="button"
                  aria-label={`Remove ${line.partNumber} from staged lines`}
                  onClick={() => handleRemoveLine(idx)}
                  style={{
                    background: 'none',
                    border: 'none',
                    cursor: 'pointer',
                    padding: 'var(--token-space-2)',
                    color: 'var(--token-text-secondary)',
                    fontSize: 'var(--token-fs-16)',
                    minHeight: '44px',
                    minWidth: '44px',
                    borderRadius: 'var(--token-radius-control)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                  }}
                >
                  ✕
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* Empty staged list */}
      {stagedLines.length === 0 && !pendingPart && !isNetworkError && !insufficientStock && (
        <p style={{ color: 'var(--token-text-secondary)', fontSize: 'var(--token-fs-13)', margin: '0 0 var(--token-space-4)', textAlign: 'center' }}>
          Search for a part above and add lines to submit.
        </p>
      )}

      {/* Submit button — single atomic submit */}
      <form onSubmit={handleSubmit}>
        <button
          type="submit"
          disabled={stagedLines.length === 0 || isSubmitting}
          aria-busy={isSubmitting}
          style={{
            width: '100%',
            padding: 'var(--token-space-4)',
            borderRadius: 'var(--token-radius-control)',
            border: 'none',
            background: stagedLines.length === 0 || isSubmitting ? 'var(--token-neutral-200)' : 'var(--token-accent-600)',
            color: stagedLines.length === 0 || isSubmitting ? 'var(--token-neutral-500)' : 'var(--token-surface-page)',
            cursor: stagedLines.length === 0 || isSubmitting ? 'not-allowed' : 'pointer',
            fontFamily: 'var(--token-family-base)',
            fontSize: 'var(--token-fs-15)',
            fontWeight: 700,
            minHeight: '52px',
            letterSpacing: '0.01em',
          }}
        >
          {isSubmitting ? 'Submitting…' : `Submit ${stagedLines.length > 0 ? `(${stagedLines.length} line${stagedLines.length !== 1 ? 's' : ''})` : ''}`}
        </button>
      </form>
    </section>
  )
}
