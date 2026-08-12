/**
 * @fileoverview OverrideReasonField — required when rank > 3 or absent from snapshot.
 *
 * Renders a labelled textarea with:
 * - Character counter (shows remaining chars, matching server limit)
 * - aria-invalid + visible error when empty and the field has been touched
 * - aria-describedby wiring the helper/error text
 */
import { OVERRIDE_REASON_MAX_LEN } from '../api/useAssignTechnician.js'

const FIELD_ID = 'override-reason'
const DESC_ID  = 'override-reason-desc'
const ERROR_ID = 'override-reason-error'

/**
 * @param {{
 *   value: string,
 *   onChange: (val: string) => void,
 *   touched?: boolean,
 *   disabled?: boolean
 * }} props
 */
export function OverrideReasonField({ value, onChange, touched = false, disabled = false }) {
  const hasError = touched && value.trim().length === 0
  const remaining = OVERRIDE_REASON_MAX_LEN - value.length
  const overLimit = remaining < 0

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-2)' }}>
      <label
        htmlFor={FIELD_ID}
        style={{
          fontSize: 'var(--token-fs-13)',
          fontWeight: 600,
          color: 'var(--token-text-primary)',
          fontFamily: 'var(--token-family-base)',
        }}
      >
        Override reason
        <span aria-hidden="true" style={{ color: 'var(--token-danger-default)', marginLeft: '0.2em' }}>*</span>
      </label>

      <span
        id={DESC_ID}
        style={{
          fontSize: 'var(--token-fs-12)',
          color: 'var(--token-text-secondary)',
          fontFamily: 'var(--token-family-base)',
        }}
      >
        This technician is outside the top-{OVERRIDE_REASON_MAX_LEN > 0 ? '3' : ''} recommendations.
        Explain why they are the best choice for this assignment.
      </span>

      <textarea
        id={FIELD_ID}
        name="overrideReason"
        rows={3}
        disabled={disabled}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        maxLength={OVERRIDE_REASON_MAX_LEN}
        aria-required="true"
        aria-invalid={hasError || overLimit ? 'true' : 'false'}
        aria-describedby={`${DESC_ID}${hasError ? ` ${ERROR_ID}` : ''}`}
        style={{
          width: '100%',
          padding: 'var(--token-space-3)',
          fontFamily: 'var(--token-family-base)',
          fontSize: 'var(--token-fs-14)',
          color: 'var(--token-text-primary)',
          background: 'var(--token-surface-input)',
          border: `1px solid ${hasError ? 'var(--token-danger-default)' : 'var(--token-border-default)'}`,
          borderRadius: 'var(--token-radius-control)',
          resize: 'vertical',
          boxSizing: 'border-box',
          outline: 'none',
        }}
        placeholder="Explain why this technician is the best choice…"
      />

      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'flex-start',
          fontFamily: 'var(--token-family-base)',
        }}
      >
        {hasError ? (
          <span
            id={ERROR_ID}
            role="alert"
            style={{ fontSize: 'var(--token-fs-12)', color: 'var(--token-danger-default)' }}
          >
            Override reason is required.
          </span>
        ) : (
          <span />
        )}
        <span
          aria-live="polite"
          aria-label={`${remaining} characters remaining`}
          style={{
            fontSize: 'var(--token-fs-12)',
            color: overLimit ? 'var(--token-danger-default)' : 'var(--token-text-secondary)',
            marginLeft: 'auto',
            fontVariantNumeric: 'var(--token-numeric)',
          }}
        >
          {remaining}
        </span>
      </div>
    </div>
  )
}
