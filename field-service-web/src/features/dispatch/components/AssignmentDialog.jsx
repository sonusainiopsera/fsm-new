/**
 * @fileoverview AssignmentDialog — confirmation dialog for assign and reassign flows.
 *
 * Modes:
 *   'assign'    POST /api/v1/work-orders/{id}/assign
 *   'reassign'  POST /api/v1/work-orders/{id}/reassignment
 *
 * State machine:
 *   idle → submitting → success (closes)
 *                     → appt_breach  (422 APPOINTMENT_BREACH_UNACKNOWLEDGED)
 *                     → cert_refused (422 CERTIFICATION_EXPIRED / CERTIFICATION_MISSING / CERTIFICATION_GUARD)
 *                     → conflict     (409)
 *                     → retry_after  (429)
 *                     → field_errors (400 with fieldErrors)
 *                     → error        (5xx / unexpected)
 *
 * The component uses the shared Modal primitive for aria-modal focus-trap
 * and focus-restoration behaviour, and derives all UI from server-supplied
 * rank — the client never decides eligibility itself.
 *
 * @module features/dispatch/components/AssignmentDialog
 */
import { useRef, useState, useCallback } from 'react'
import { Modal } from '../../../components/index.js'
import { useToast } from '../../../components/index.js'
import {
  useAssignTechnician,
  useReassignTechnician,
  isOverrideRequired,
  generateDialogKey,
} from '../api/useAssignTechnician.js'
import { OverrideReasonField } from './OverrideReasonField.jsx'
import { ReassignmentReasonSelect } from './ReassignmentReasonSelect.jsx'
import { AppointmentImpactAcknowledgement } from './AppointmentImpactAcknowledgement.jsx'

// ── Error code constants ──────────────────────────────────────────────────────

const APPT_BREACH_CODE   = 'APPOINTMENT_BREACH_UNACKNOWLEDGED'
const CERT_CODES         = new Set([
  'CERTIFICATION_EXPIRED', 'CERTIFICATION_MISSING', 'CERTIFICATION_GUARD',
  'CERTIFICATION_GUARD_REFUSED',
])
const CONFLICT_CODES     = new Set([
  'REASSIGNMENT_INVALID_STATE', 'WORK_ORDER_VERSION_CONFLICT',
  'WORK_ORDER_ILLEGAL_TRANSITION', 'CONFLICT',
])

// ── Types ─────────────────────────────────────────────────────────────────────

/**
 * @typedef {'idle'|'submitting'|'appt_breach'|'cert_refused'|'conflict'|'retry_after'|'field_errors'|'error'} DialogPhase
 */

// ── Main component ────────────────────────────────────────────────────────────

/**
 * @param {{
 *   open: boolean,
 *   mode: 'assign' | 'reassign',
 *   workOrderId: string,
 *   candidate: import('../api/useRecommendations.js').CandidateDto,
 *   snapshotId?: string | null,
 *   onClose: () => void,
 *   onSuccess?: () => void,
 *   onRefreshRecommendations?: () => void
 * }} props
 */
export function AssignmentDialog({
  open,
  mode,
  workOrderId,
  candidate,
  snapshotId = null,
  onClose,
  onSuccess,
  onRefreshRecommendations,
}) {
  const { toast } = useToast()

  // Stable per-session idempotency key — generated once on mount, reused across retries
  const idempotencyKeyRef = useRef(generateDialogKey())

  // Form state
  const [overrideReason, setOverrideReason]   = useState('')
  const [reassignReason, setReassignReason]   = useState('')
  const [reasonNotes,    setReasonNotes]      = useState('')
  const [apptAck,        setApptAck]          = useState('')

  // Touched flags (only show validation errors after first submit attempt)
  const [touchedOverride,  setTouchedOverride]  = useState(false)
  const [touchedReassign,  setTouchedReassign]  = useState(false)
  const [touchedApptAck,   setTouchedApptAck]   = useState(false)

  // Dialog phase + server-returned error info
  const [phase,       setPhase]       = useState(/** @type {DialogPhase} */ ('idle'))
  const [serverError, setServerError] = useState(/** @type {null | { code: string, message: string, fieldErrors: any[], traceId?: string, retryAfter?: number }} */ (null))

  const overrideNeeded = isOverrideRequired(candidate?.rank)
  const showApptAck    = phase === 'appt_breach'

  // ── Derived confirm-button validity ──────────────────────────────────────────

  const overrideValid  = !overrideNeeded || overrideReason.trim().length > 0
  const reassignValid  = mode !== 'reassign' || reassignReason !== ''
  const apptAckValid   = !showApptAck || apptAck.trim().length > 0
  const formValid      = overrideValid && reassignValid && apptAckValid
  const isSubmitting   = phase === 'submitting'
  const certRefused    = phase === 'cert_refused'
  const confirmDisabled = !formValid || isSubmitting || certRefused

  // ── Error handler (shared by both mutations) ──────────────────────────────

  const handleError = useCallback((err) => {
    const code = err?.code ?? err?.body?.code ?? ''
    const message = err?.message ?? 'An unexpected error occurred.'
    const fieldErrors = err?.fieldErrors ?? err?.body?.fieldErrors ?? []
    const traceId = err?.traceId ?? err?.body?.traceId

    if (code === APPT_BREACH_CODE) {
      setPhase('appt_breach')
      setServerError({ code, message, fieldErrors, traceId })
      return
    }
    if (CERT_CODES.has(code)) {
      setPhase('cert_refused')
      setServerError({ code, message, fieldErrors, traceId })
      return
    }
    if (CONFLICT_CODES.has(code) || err?.status === 409) {
      setPhase('conflict')
      setServerError({ code, message, fieldErrors, traceId })
      return
    }
    if (err?.status === 429) {
      const retryAfter = parseInt(err?.headers?.get?.('Retry-After') ?? '60', 10)
      setPhase('retry_after')
      setServerError({ code, message, fieldErrors, traceId, retryAfter })
      return
    }
    if (fieldErrors.length > 0 || err?.status === 400) {
      setPhase('field_errors')
      setServerError({ code, message, fieldErrors, traceId })
      return
    }
    setPhase('error')
    setServerError({ code, message, fieldErrors, traceId })
  }, [])

  // ── Success handler ────────────────────────────────────────────────────────

  const handleSuccess = useCallback(() => {
    const name = candidate?.technicianName ?? candidate?.technicianId ?? 'Technician'
    toast(
      mode === 'assign'
        ? `${name} has been assigned.`
        : `Work order reassigned to ${name}.`,
      'success',
    )
    onSuccess?.()
    onClose()
  }, [candidate, mode, toast, onSuccess, onClose])

  // ── Mutations ─────────────────────────────────────────────────────────────

  const assignMutation = useAssignTechnician({
    workOrderId,
    idempotencyKey: idempotencyKeyRef.current,
    onSuccess: handleSuccess,
    onError:   handleError,
  })

  const reassignMutation = useReassignTechnician({
    workOrderId,
    idempotencyKey: idempotencyKeyRef.current,
    onSuccess: handleSuccess,
    onError:   handleError,
  })

  // ── Submit ────────────────────────────────────────────────────────────────

  const handleSubmit = useCallback(() => {
    // Mark all required fields as touched to surface validation errors
    setTouchedOverride(true)
    if (mode === 'reassign') setTouchedReassign(true)
    if (showApptAck) setTouchedApptAck(true)

    if (!formValid) return

    setPhase('submitting')

    if (mode === 'assign') {
      assignMutation.mutate({
        technicianId: candidate.technicianId,
        recommendationSnapshotId: snapshotId ?? null,
        overrideReason: overrideNeeded ? overrideReason : null,
        expectedVersion: 0,
      })
    } else {
      reassignMutation.mutate({
        technicianId: candidate.technicianId,
        reassignmentReason: reassignReason,
        reasonNotes: reasonNotes || null,
        recommendationSnapshotId: snapshotId ?? null,
        overrideReason: overrideNeeded ? overrideReason : null,
        appointmentImpactAcknowledgement: showApptAck ? apptAck : null,
        expectedVersion: 0,
      })
    }
  }, [
    mode, formValid, showApptAck, overrideNeeded,
    assignMutation, reassignMutation,
    candidate, snapshotId, overrideReason, reassignReason, reasonNotes, apptAck,
  ])

  // ── Conflict refresh ──────────────────────────────────────────────────────

  const handleRefresh = useCallback(() => {
    onRefreshRecommendations?.()
    onClose()
  }, [onRefreshRecommendations, onClose])

  // ── Render ────────────────────────────────────────────────────────────────

  if (!open || !candidate) return null

  const scorePct = Math.round(Math.min(1, Math.max(0, candidate.score)) * 100)
  const label    = candidate.technicianName ?? candidate.technicianId
  const title    = mode === 'assign' ? 'Confirm assignment' : 'Reassign technician'

  return (
    <Modal open={open} onClose={onClose} title={title} size="md">
      <div
        style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-5)', fontFamily: 'var(--token-family-base)' }}
        data-testid="assignment-dialog-body"
      >

        {/* Technician summary panel */}
        <TechnicianSummaryPanel
          label={label}
          rank={candidate.rank}
          scorePct={scorePct}
          partsAvailability={candidate.partsAvailability}
          travelDegraded={candidate.travelEstimateDegraded}
        />

        {/* Override reason — shown when rank > 3 or absent */}
        {overrideNeeded && (
          <OverrideReasonField
            value={overrideReason}
            onChange={setOverrideReason}
            touched={touchedOverride}
            disabled={isSubmitting || certRefused}
          />
        )}

        {/* Reassignment reason + notes — reassign mode only */}
        {mode === 'reassign' && (
          <ReassignmentReasonSelect
            reason={reassignReason}
            notes={reasonNotes}
            onReasonChange={setReassignReason}
            onNotesChange={setReasonNotes}
            touched={touchedReassign}
            disabled={isSubmitting || certRefused}
          />
        )}

        {/* Appointment impact acknowledgement — only after 422 APPOINTMENT_BREACH */}
        {showApptAck && (
          <AppointmentImpactAcknowledgement
            value={apptAck}
            onChange={setApptAck}
            touched={touchedApptAck}
            disabled={isSubmitting}
          />
        )}

        {/* Server error states */}
        {phase === 'cert_refused' && serverError && (
          <CertRefusedBanner message={serverError.message} />
        )}
        {phase === 'conflict' && (
          <ConflictBanner onRefresh={handleRefresh} />
        )}
        {phase === 'retry_after' && serverError && (
          <RetryAfterBanner retryAfterSeconds={serverError.retryAfter ?? 60} />
        )}
        {phase === 'field_errors' && serverError?.fieldErrors?.length > 0 && (
          <FieldErrorsBanner fieldErrors={serverError.fieldErrors} />
        )}
        {phase === 'error' && serverError && (
          <ErrorBanner message={serverError.message} traceId={serverError.traceId} />
        )}

        {/* Footer actions */}
        <DialogFooter
          mode={mode}
          phase={phase}
          confirmDisabled={confirmDisabled}
          onSubmit={handleSubmit}
          onClose={onClose}
          onRefresh={handleRefresh}
        />
      </div>
    </Modal>
  )
}

// ── Sub-panels ────────────────────────────────────────────────────────────────

/**
 * @param {{
 *   label: string,
 *   rank: number,
 *   scorePct: number,
 *   partsAvailability: import('../api/useRecommendations.js').PartsAvailabilitySummary | null,
 *   travelDegraded: boolean
 * }} props
 */
function TechnicianSummaryPanel({ label, rank, scorePct, partsAvailability, travelDegraded }) {
  const partsWarning = partsAvailability && partsAvailability.status !== 'AVAILABLE'

  return (
    <section
      aria-label="Selected technician"
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 'var(--token-space-2)',
        padding: 'var(--token-space-4)',
        borderRadius: 'var(--token-radius-card)',
        background: 'var(--token-surface-raised)',
        border: '1px solid var(--token-border-default)',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--token-space-3)' }}>
        {/* Rank badge */}
        <span
          aria-label={`Rank ${rank}`}
          style={{
            flexShrink: 0,
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: '2rem',
            height: '2rem',
            borderRadius: 'var(--token-radius-pill)',
            background: 'var(--token-neutral-200)',
            color: 'var(--token-text-primary)',
            fontWeight: 700,
            fontSize: 'var(--token-fs-14)',
            fontVariantNumeric: 'var(--token-numeric)',
          }}
        >
          {rank}
        </span>

        {/* Name */}
        <span
          style={{
            flex: 1,
            fontSize: 'var(--token-fs-16)',
            fontWeight: 600,
            color: 'var(--token-text-primary)',
          }}
        >
          {label}
        </span>

        {/* Travel degraded indicator */}
        {travelDegraded && (
          <span
            aria-label="Travel estimate only — live travel data unavailable"
            title="Travel estimate only"
            style={{
              fontSize: 'var(--token-fs-12)',
              color: 'var(--token-warning-emphasis)',
              border: '1px solid var(--token-warning-default)',
              borderRadius: 'var(--token-radius-control)',
              padding: '2px var(--token-space-2)',
              whiteSpace: 'nowrap',
            }}
          >
            ~travel est.
          </span>
        )}

        {/* Score */}
        <span
          aria-label={`Composite score: ${scorePct} out of 100`}
          style={{
            fontSize: 'var(--token-fs-18)',
            fontWeight: 700,
            fontVariantNumeric: 'var(--token-numeric)',
            color: 'var(--token-text-primary)',
          }}
        >
          {scorePct}
          <span
            style={{
              fontSize: 'var(--token-fs-12)',
              color: 'var(--token-text-secondary)',
              marginLeft: '2px',
            }}
          >
            /100
          </span>
        </span>
      </div>

      {/* Parts warning */}
      {partsWarning && (
        <div
          aria-describedby="dialog-parts-warning"
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 'var(--token-space-2)',
            padding: 'var(--token-space-2) var(--token-space-3)',
            borderRadius: 'var(--token-radius-control)',
            background: 'var(--token-warning-subtle)',
            border: '1px solid var(--token-warning-default)',
            fontSize: 'var(--token-fs-12)',
            color: 'var(--token-warning-emphasis)',
          }}
        >
          <span aria-hidden="true">⚠</span>
          <span id="dialog-parts-warning">
            {partsAvailability.status === 'PARTIAL'
              ? `Parts partially available (${Math.round(partsAvailability.satisfactionRatio * 100)}%)`
              : 'Parts unavailable for this technician'}
            {partsAvailability.shortfalls?.length > 0 &&
              ` — ${partsAvailability.shortfalls.length} part${partsAvailability.shortfalls.length !== 1 ? 's' : ''} short`}
          </span>
        </div>
      )}
    </section>
  )
}

/**
 * @param {{ message: string }} props
 */
function CertRefusedBanner({ message }) {
  return (
    <div
      role="alert"
      data-testid="cert-refused-banner"
      style={{
        display: 'flex',
        gap: 'var(--token-space-2)',
        padding: 'var(--token-space-4)',
        borderRadius: 'var(--token-radius-card)',
        background: 'var(--token-danger-subtle)',
        border: '1px solid var(--token-danger-default)',
        color: 'var(--token-danger-emphasis)',
        fontFamily: 'var(--token-family-base)',
        fontSize: 'var(--token-fs-14)',
      }}
    >
      <span aria-hidden="true">⊘</span>
      <div>
        <p style={{ margin: 0, fontWeight: 600 }}>Assignment refused — certification requirement not met</p>
        <p style={{ margin: 'var(--token-space-1) 0 0' }}>{message}</p>
        <p style={{ margin: 'var(--token-space-2) 0 0', fontSize: 'var(--token-fs-12)', color: 'var(--token-text-secondary)' }}>
          This is a hard requirement and cannot be overridden. Select a different technician.
        </p>
      </div>
    </div>
  )
}

/**
 * @param {{ onRefresh: () => void }} props
 */
function ConflictBanner({ onRefresh }) {
  return (
    <div
      role="alert"
      data-testid="conflict-banner"
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 'var(--token-space-3)',
        padding: 'var(--token-space-4)',
        borderRadius: 'var(--token-radius-card)',
        background: 'var(--token-warning-subtle)',
        border: '1px solid var(--token-warning-default)',
        color: 'var(--token-warning-emphasis)',
        fontFamily: 'var(--token-family-base)',
        fontSize: 'var(--token-fs-14)',
      }}
    >
      <p style={{ margin: 0, fontWeight: 600 }}>Work order state changed</p>
      <p style={{ margin: 0 }}>
        This work order was updated by another dispatcher while you had the dialog open.
        Refresh the recommendation list to continue.
      </p>
      <button
        type="button"
        onClick={onRefresh}
        style={{
          alignSelf: 'flex-start',
          padding: 'var(--token-space-2) var(--token-space-4)',
          fontSize: 'var(--token-fs-13)',
          fontFamily: 'var(--token-family-base)',
          fontWeight: 600,
          borderRadius: 'var(--token-radius-control)',
          border: '1px solid var(--token-warning-default)',
          background: 'transparent',
          color: 'var(--token-warning-emphasis)',
          cursor: 'pointer',
        }}
      >
        Refresh recommendations
      </button>
    </div>
  )
}

/**
 * @param {{ retryAfterSeconds: number }} props
 */
function RetryAfterBanner({ retryAfterSeconds }) {
  return (
    <div
      role="alert"
      data-testid="retry-after-banner"
      style={{
        padding: 'var(--token-space-4)',
        borderRadius: 'var(--token-radius-card)',
        background: 'var(--token-warning-subtle)',
        border: '1px solid var(--token-warning-default)',
        color: 'var(--token-warning-emphasis)',
        fontFamily: 'var(--token-family-base)',
        fontSize: 'var(--token-fs-14)',
      }}
    >
      <p style={{ margin: 0, fontWeight: 600 }}>Too many requests</p>
      <p style={{ margin: 'var(--token-space-1) 0 0' }}>
        Please wait {retryAfterSeconds} second{retryAfterSeconds !== 1 ? 's' : ''} before trying again.
      </p>
    </div>
  )
}

/**
 * @param {{ fieldErrors: Array<{ field: string, message: string }> }} props
 */
function FieldErrorsBanner({ fieldErrors }) {
  return (
    <div
      role="alert"
      data-testid="field-errors-banner"
      style={{
        padding: 'var(--token-space-4)',
        borderRadius: 'var(--token-radius-card)',
        background: 'var(--token-danger-subtle)',
        border: '1px solid var(--token-danger-default)',
        color: 'var(--token-danger-emphasis)',
        fontFamily: 'var(--token-family-base)',
        fontSize: 'var(--token-fs-14)',
      }}
    >
      <p style={{ margin: '0 0 var(--token-space-2)', fontWeight: 600 }}>Validation failed</p>
      <ul style={{ margin: 0, paddingLeft: 'var(--token-space-5)' }}>
        {fieldErrors.map((fe, i) => (
          <li key={i}>
            <strong>{fe.field}:</strong> {fe.message}
          </li>
        ))}
      </ul>
    </div>
  )
}

/**
 * @param {{ message: string, traceId?: string }} props
 */
function ErrorBanner({ message, traceId }) {
  return (
    <div
      role="alert"
      data-testid="error-banner"
      style={{
        padding: 'var(--token-space-4)',
        borderRadius: 'var(--token-radius-card)',
        background: 'var(--token-danger-subtle)',
        border: '1px solid var(--token-danger-default)',
        color: 'var(--token-danger-emphasis)',
        fontFamily: 'var(--token-family-base)',
        fontSize: 'var(--token-fs-14)',
      }}
    >
      <p style={{ margin: 0, fontWeight: 600 }}>Submission failed</p>
      <p style={{ margin: 'var(--token-space-1) 0 0' }}>{message}</p>
      {traceId && (
        <p style={{ margin: 'var(--token-space-2) 0 0', fontSize: 'var(--token-fs-11)', color: 'var(--token-text-secondary)' }}>
          Trace ID: {traceId}
        </p>
      )}
    </div>
  )
}

/**
 * @param {{
 *   mode: 'assign' | 'reassign',
 *   phase: DialogPhase,
 *   confirmDisabled: boolean,
 *   onSubmit: () => void,
 *   onClose: () => void,
 *   onRefresh: () => void
 * }} props
 */
function DialogFooter({ mode, phase, confirmDisabled, onSubmit, onClose, onRefresh }) {
  const isSubmitting = phase === 'submitting'
  const certRefused  = phase === 'cert_refused'
  const isConflict   = phase === 'conflict'

  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'flex-end',
        gap: 'var(--token-space-3)',
        paddingTop: 'var(--token-space-4)',
        borderTop: '1px solid var(--token-border-default)',
        fontFamily: 'var(--token-family-base)',
      }}
    >
      <button
        type="button"
        onClick={onClose}
        style={{
          padding: 'var(--token-space-2) var(--token-space-5)',
          fontSize: 'var(--token-fs-14)',
          fontFamily: 'var(--token-family-base)',
          borderRadius: 'var(--token-radius-control)',
          border: '1px solid var(--token-border-default)',
          background: 'var(--token-surface-card)',
          color: 'var(--token-text-primary)',
          cursor: 'pointer',
        }}
      >
        {certRefused || isConflict ? 'Close' : 'Cancel'}
      </button>

      {/* Conflict: show Refresh instead of Confirm */}
      {isConflict ? (
        <button
          type="button"
          onClick={onRefresh}
          style={{
            padding: 'var(--token-space-2) var(--token-space-5)',
            fontSize: 'var(--token-fs-14)',
            fontFamily: 'var(--token-family-base)',
            fontWeight: 600,
            borderRadius: 'var(--token-radius-control)',
            border: '1px solid var(--token-accent-500)',
            background: 'var(--token-accent-500)',
            color: 'var(--token-on-accent)',
            cursor: 'pointer',
          }}
        >
          Refresh recommendations
        </button>
      ) : !certRefused && (
        <button
          type="button"
          onClick={onSubmit}
          disabled={confirmDisabled}
          aria-busy={isSubmitting}
          aria-disabled={confirmDisabled}
          style={{
            padding: 'var(--token-space-2) var(--token-space-5)',
            fontSize: 'var(--token-fs-14)',
            fontFamily: 'var(--token-family-base)',
            fontWeight: 600,
            borderRadius: 'var(--token-radius-control)',
            border: '1px solid var(--token-accent-500)',
            background: confirmDisabled ? 'var(--token-neutral-300)' : 'var(--token-accent-500)',
            color: confirmDisabled ? 'var(--token-text-secondary)' : 'var(--token-on-accent)',
            cursor: confirmDisabled ? 'default' : 'pointer',
            opacity: confirmDisabled ? 0.6 : 1,
          }}
        >
          {isSubmitting
            ? 'Submitting…'
            : mode === 'assign'
            ? 'Confirm assignment'
            : phase === 'appt_breach'
            ? 'Confirm with acknowledgement'
            : 'Confirm reassignment'}
        </button>
      )}
    </div>
  )
}
