/**
 * @fileoverview EmailInput — email address field wired through FormField.
 *
 * @param {{
 *   value: string,
 *   onChange: (e: import('react').ChangeEvent<HTMLInputElement>) => void,
 *   errors?: string[],
 *   disabled?: boolean
 * }} props
 */

import { FormField } from '../../../components/index.js'

export function EmailInput({ value, onChange, errors = [], disabled = false }) {
  return (
    <FormField label="Email address" required errors={errors} id="sign-in-email">
      {({ id, 'aria-describedby': ariaDescribedby, 'aria-invalid': ariaInvalid, 'aria-required': ariaRequired }) => (
        <input
          id={id}
          type="email"
          autoComplete="email"
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
            width: '100%',
            boxSizing: 'border-box',
            minHeight: '44px',
          }}
        />
      )}
    </FormField>
  )
}
