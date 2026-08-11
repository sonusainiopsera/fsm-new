/**
 * @fileoverview PasswordInput — password field with show/hide toggle.
 * Toggle is a 44 px touch target (AC-9) that switches input type between
 * password and text without refocusing the input.
 *
 * @param {{
 *   value: string,
 *   onChange: (e: import('react').ChangeEvent<HTMLInputElement>) => void,
 *   show: boolean,
 *   onToggleShow: () => void,
 *   errors?: string[],
 *   disabled?: boolean
 * }} props
 */

import { FormField } from '../../../components/index.js'

export function PasswordInput({ value, onChange, show, onToggleShow, errors = [], disabled = false }) {
  return (
    <FormField label="Password" required errors={errors} id="sign-in-password">
      {({ id, 'aria-describedby': ariaDescribedby, 'aria-invalid': ariaInvalid, 'aria-required': ariaRequired }) => (
        <div style={{ position: 'relative', display: 'flex', alignItems: 'center' }}>
          <input
            id={id}
            type={show ? 'text' : 'password'}
            autoComplete="current-password"
            value={value}
            onChange={onChange}
            disabled={disabled}
            aria-describedby={ariaDescribedby}
            aria-invalid={ariaInvalid}
            aria-required={ariaRequired}
            style={{
              fontFamily: 'var(--token-family-base)',
              fontSize: 'var(--token-fs-14)',
              color: 'var(--token-text-primary)',
              background: 'var(--token-surface-base)',
              border: '1px solid var(--token-border-default)',
              borderRadius: 'var(--token-radius-control)',
              padding: 'var(--token-space-2) var(--token-space-3)',
              paddingRight: 'var(--token-space-10)',
              width: '100%',
              boxSizing: 'border-box',
              minHeight: '44px',
            }}
          />
          <button
            type="button"
            aria-label={show ? 'Hide password' : 'Show password'}
            aria-controls={id}
            onClick={onToggleShow}
            disabled={disabled}
            style={{
              position: 'absolute',
              right: 'var(--token-space-2)',
              background: 'none',
              border: 'none',
              cursor: disabled ? 'not-allowed' : 'pointer',
              fontFamily: 'var(--token-family-base)',
              fontSize: 'var(--token-fs-13)',
              color: 'var(--token-text-secondary)',
              minHeight: '44px',
              minWidth: '44px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              borderRadius: 'var(--token-radius-control)',
            }}
          >
            {show ? 'Hide' : 'Show'}
          </button>
        </div>
      )}
    </FormField>
  )
}
