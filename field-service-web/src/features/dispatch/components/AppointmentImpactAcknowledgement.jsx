/**
 * @fileoverview AppointmentImpactAcknowledgement — revealed only on 422 APPOINTMENT_BREACH_UNACKNOWLEDGED.
 *
 * The dialog NEVER pre-fills or auto-acknowledges. The dispatcher must
 * type an explicit acknowledgement text before resubmission is enabled.
 */
import { APPT_ACK_MAX_LEN } from '../api/useAssignTechnician.js'

const FIELD_ID  = 'appt-impact-ack'
const DESC_ID   = 'appt-impact-ack-desc'
const ERROR_ID  = 'appt-impact-ack-error'

/**
 * @param {{
 *   value: string,
 *   onChange: (val: string) => void,
 *   touched?: boolean,
 *   disabled?: boolean
 * }} props
 */
export function AppointmentImpactAcknowledgement({ value, onChange, touched = false, disabled = false }) {
  const hasError = touched && value.trim().length === 0
  const remaining = APPT_ACK_MAX_LEN - value.length
  const overLimit = remaining < 0

  return (
    <div
      role="alert"
      aria-live="polite"
      data-testid="appointment-ack-section"
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 'var(--token-space-3)',
        padding: 'var(--token-space-4)',
        borderRadius: 'var(--token-radius-card)',
        background: 'var(--token-warning-subtle)',
        border: '1px solid var(--token-warning-default)',
      }}
    >
      {/* Warning header */}
      <div
        style={{
          display: 'flex',
          alignItems: 'flex-start',
          gap: 'var(--token-space-2)',
          fontFamily: 'var(--token-family-base)',
        }}
      >
        <span aria-hidden="true" style={{ fontSize: 'var(--token-fs-16)' }}>⚠</span>
        <div>
          <p
            style={{
              margin: 0,
              fontSize: 'var(--token-fs-14)',
              fontWeight: 600,
              color: 'var(--token-warning-emphasis)',
            }}
          >
            This work order has a confirmed appointment window
          </p>
          <p
            id={DESC_ID}
            style={{
              margin: 'var(--token-space-1) 0 0',
              fontSize: 'var(--token-fs-13)',
              color: 'var(--token-warning-emphasis)',
            }}
          >
            Reassigning will affect the customer's confirmed appointment.
            You must acknowledge this impact before resubmitting.
          </p>
        </div>
      </div>

      {/* Acknowledgement textarea */}
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
          Appointment impact acknowledgement
          <span aria-hidden="true" style={{ color: 'var(--token-danger-default)', marginLeft: '0.2em' }}>*</span>
        </label>

        <textarea
          id={FIELD_ID}
          name="appointmentImpactAcknowledgement"
          rows={3}
          disabled={disabled}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          maxLength={APPT_ACK_MAX_LEN}
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
          }}
          placeholder="Describe the steps taken to notify the customer and mitigate the appointment impact…"
        />

        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            fontFamily: 'var(--token-family-base)',
          }}
        >
          {hasError ? (
            <span
              id={ERROR_ID}
              role="alert"
              style={{ fontSize: 'var(--token-fs-12)', color: 'var(--token-danger-default)' }}
            >
              Acknowledgement text is required.
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
    </div>
  )
}
