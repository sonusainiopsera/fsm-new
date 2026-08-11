/**
 * @fileoverview DetailDrawer — work order detail overlay for the dispatcher board.
 *
 * AC-4: Deep-linkable (id in URL), focus-trapped modal dialog, closes on Escape
 * and overlay click, returns focus to the invoking row on close.
 * AC-5: Lifecycle action buttons rendered strictly from legalNextEvents array.
 *       No client-side lifecycle rule duplication.
 * AC-6: Inline error messages per refusal type.
 *
 * Uses the shared DetailDrawer component as the focus-trap/scrim container.
 */
import { useState } from 'react'
import { DetailDrawer as BaseDrawer, Chip } from '../../../components/index.js'
import { useWorkOrderTransition, mapTransitionError } from '../api/useWorkOrderTransition.js'
import { generateAttemptKey } from '../../../lib/idempotency.js'

/** Human-readable labels for lifecycle events. */
const EVENT_LABELS = {
  ASSIGN: 'Assign',
  DEPART: 'Depart',
  START: 'Start',
  HOLD: 'Place on Hold',
  RESUME: 'Resume',
  COMPLETE: 'Complete',
  CLOSE: 'Close',
  CANCEL: 'Cancel',
  REASSIGN: 'Reassign',
}

/**
 * Returns the button variant for a lifecycle event.
 * @param {string} event
 * @returns {{ color: string, border: string }}
 */
function eventStyle(event) {
  if (event === 'CANCEL') {
    return { color: 'var(--token-danger-emphasis)', border: '1px solid var(--token-danger-default)' }
  }
  if (event === 'COMPLETE' || event === 'CLOSE') {
    return { color: 'var(--token-success-emphasis)', border: '1px solid var(--token-success-default)' }
  }
  return { color: 'var(--token-accent-700)', border: '1px solid var(--token-accent-400)' }
}

/**
 * @param {{
 *   workOrder: import('../api/useWorkOrderSearch.js').WorkOrderBoardRow | null,
 *   open: boolean,
 *   onClose: () => void
 * }} props
 */
export function WorkOrderDetailDrawer({ workOrder, open, onClose }) {
  const [actionError, setActionError] = useState(/** @type {import('../api/useWorkOrderTransition.js').TransitionError | null} */ (null))
  const [idempotencyKey, setIdempotencyKey] = useState(() => generateAttemptKey())
  const mutation = useWorkOrderTransition()

  if (!workOrder) return null

  const stateValue = workOrder.state?.toLowerCase()
  const priorityValue = workOrder.priority?.toLowerCase()

  async function handleAction(event) {
    setActionError(null)
    try {
      await mutation.mutateAsync({
        workOrderId: workOrder.id,
        event,
        expectedVersion: workOrder.version,
        idempotencyKey,
      })
      setIdempotencyKey(generateAttemptKey())
      // Successful action — close drawer so board re-renders fresh data
      onClose()
    } catch (err) {
      const typed = err && err.variant ? err : mapTransitionError(err)
      setActionError(typed)
      // For version conflict, generate a new idempotency key (it's a new attempt)
      if (typed.variant === 'version_conflict') {
        setIdempotencyKey(generateAttemptKey())
      }
    }
  }

  return (
    <BaseDrawer
      open={open}
      onClose={onClose}
      title={workOrder.reference ?? workOrder.id}
      width="520px"
    >
      <div
        style={{ padding: 'var(--token-space-4)', display: 'flex', flexDirection: 'column', gap: 'var(--token-space-4)' }}
        aria-label={`Work order detail: ${workOrder.reference ?? workOrder.id}`}
      >
        {/* Title & chips */}
        <div>
          <h3
            style={{ margin: '0 0 var(--token-space-2)', fontSize: 'var(--token-fs-16)', fontWeight: 600, color: 'var(--token-text-primary)' }}
          >
            {workOrder.title}
          </h3>
          <div style={{ display: 'flex', gap: 'var(--token-space-2)', flexWrap: 'wrap' }}>
            <Chip kind="state" value={stateValue} />
            <Chip kind="priority" value={priorityValue} />
            {workOrder.atRisk && (
              <span
                role="status"
                aria-label="At risk"
                style={{
                  display: 'inline-flex', alignItems: 'center', gap: 'var(--token-space-1)',
                  fontSize: 'var(--token-fs-13)', color: 'var(--token-danger-emphasis)',
                  background: 'var(--token-danger-subtle)', border: '1px solid var(--token-danger-default)',
                  borderRadius: 'var(--token-radius-pill)', padding: 'var(--token-space-1) var(--token-space-3)',
                  fontWeight: 500,
                }}
              >
                ⚠ At-risk
              </span>
            )}
          </div>
        </div>

        {/* Details */}
        <dl style={{ margin: 0, display: 'grid', gridTemplateColumns: 'max-content 1fr', gap: 'var(--token-space-1) var(--token-space-4)', fontSize: 'var(--token-fs-14)' }}>
          <dt style={{ color: 'var(--token-text-secondary)', fontWeight: 500 }}>Customer</dt>
          <dd style={{ margin: 0 }}>{workOrder.customerName ?? '—'}</dd>

          <dt style={{ color: 'var(--token-text-secondary)', fontWeight: 500 }}>Site</dt>
          <dd style={{ margin: 0 }}>{workOrder.siteName ?? '—'}</dd>

          <dt style={{ color: 'var(--token-text-secondary)', fontWeight: 500 }}>Technician</dt>
          <dd style={{ margin: 0 }}>{workOrder.assignedTechnicianName ?? <em style={{ color: 'var(--token-text-disabled)' }}>Unassigned</em>}</dd>

          <dt style={{ color: 'var(--token-text-secondary)', fontWeight: 500 }}>Response due</dt>
          <dd style={{ margin: 0, fontVariantNumeric: 'var(--token-numeric)' }}>
            {workOrder.responseDueAt
              ? new Date(workOrder.responseDueAt).toLocaleString()
              : '—'}
          </dd>

          <dt style={{ color: 'var(--token-text-secondary)', fontWeight: 500 }}>Resolution due</dt>
          <dd style={{ margin: 0, fontVariantNumeric: 'var(--token-numeric)' }}>
            {workOrder.resolutionDueAt
              ? new Date(workOrder.resolutionDueAt).toLocaleString()
              : '—'}
          </dd>
        </dl>

        {/* Inline error */}
        {actionError && (
          <div
            role="alert"
            aria-live="assertive"
            style={{
              padding: 'var(--token-space-3)',
              background: 'var(--token-danger-subtle)',
              border: '1px solid var(--token-danger-default)',
              borderRadius: 'var(--token-radius-card)',
              fontSize: 'var(--token-fs-13)',
              color: 'var(--token-danger-emphasis)',
            }}
          >
            <strong>
              {actionError.variant === 'version_conflict' ? 'Action failed — reload required' : 'Action refused'}
            </strong>
            <p style={{ margin: 'var(--token-space-1) 0 0' }}>{actionError.message}</p>
          </div>
        )}

        {/* Lifecycle actions — rendered strictly from server legalNextEvents */}
        {workOrder.legalNextEvents?.length > 0 && (
          <div>
            <h4
              style={{ margin: '0 0 var(--token-space-2)', fontSize: 'var(--token-fs-13)', color: 'var(--token-text-secondary)', fontWeight: 500, textTransform: 'uppercase', letterSpacing: '0.04em' }}
            >
              Available actions
            </h4>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--token-space-2)' }}>
              {workOrder.legalNextEvents.map(event => {
                const style = eventStyle(event)
                return (
                  <button
                    key={event}
                    type="button"
                    disabled={mutation.isPending}
                    onClick={() => handleAction(event)}
                    aria-label={`${EVENT_LABELS[event] ?? event} work order`}
                    style={{
                      padding: 'var(--token-space-2) var(--token-space-4)',
                      fontSize: 'var(--token-fs-13)',
                      fontWeight: 500,
                      cursor: mutation.isPending ? 'not-allowed' : 'pointer',
                      background: 'var(--token-surface-card)',
                      borderRadius: 'var(--token-radius-sm)',
                      opacity: mutation.isPending ? 0.6 : 1,
                      ...style,
                    }}
                  >
                    {mutation.isPending ? '…' : (EVENT_LABELS[event] ?? event)}
                  </button>
                )
              })}
            </div>
          </div>
        )}
      </div>
    </BaseDrawer>
  )
}
