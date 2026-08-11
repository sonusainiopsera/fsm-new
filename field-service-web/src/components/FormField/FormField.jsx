/**
 * @fileoverview FormField — label, help text, required indicator, aria-invalid, inline errors.
 * Renders structured server-side 400 field-level error payload.
 */

/**
 * @typedef {{ field: string, message: string }} FieldError
 */

let _idCounter = 0
function useId(prefix = 'ff') {
  // Simple stable ID for SSR-safe usage in tests
  const id = ++_idCounter
  return `${prefix}-${id}`
}

/**
 * @param {{
 *   label: string,
 *   required?: boolean,
 *   helpText?: string,
 *   errors?: string[],
 *   fieldErrors?: FieldError[],
 *   children: (inputProps: {
 *     id: string,
 *     'aria-describedby': string,
 *     'aria-invalid': boolean | 'true' | 'false',
 *     'aria-required': boolean
 *   }) => React.ReactNode,
 *   id?: string
 * }} props
 */
export function FormField({ label, required = false, helpText, errors = [], fieldErrors = [], children, id: providedId }) {
  const baseId = providedId ?? `ff-${label.toLowerCase().replace(/\s+/g, '-')}`
  const helpId = `${baseId}-help`
  const errId = `${baseId}-err`

  const allErrors = [
    ...errors,
    ...fieldErrors.map(fe => fe.message),
  ]
  const hasError = allErrors.length > 0
  const describedByParts = []
  if (helpText) describedByParts.push(helpId)
  if (hasError) describedByParts.push(errId)

  return (
    <div
      data-component="form-field"
      data-invalid={hasError ? 'true' : undefined}
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 'var(--token-space-1)',
        fontFamily: 'var(--token-family-base)',
      }}
    >
      <label
        htmlFor={baseId}
        style={{
          fontSize: 'var(--token-fs-14)',
          fontWeight: 500,
          color: 'var(--token-text-primary)',
          display: 'flex',
          alignItems: 'center',
          gap: 'var(--token-space-1)',
        }}
      >
        {label}
        {required && (
          <span
            aria-hidden="true"
            style={{ color: 'var(--token-danger-default)', fontSize: 'var(--token-fs-14)' }}
          >
            *
          </span>
        )}
        {required && <span className="sr-only">required</span>}
      </label>

      {helpText && (
        <span
          id={helpId}
          style={{
            fontSize: 'var(--token-fs-13)',
            color: 'var(--token-text-secondary)',
          }}
        >
          {helpText}
        </span>
      )}

      {children({
        id: baseId,
        'aria-describedby': describedByParts.join(' ') || undefined,
        'aria-invalid': hasError ? 'true' : 'false',
        'aria-required': required,
      })}

      {hasError && (
        <ul
          id={errId}
          role="alert"
          aria-live="polite"
          style={{
            margin: 0,
            padding: 0,
            listStyle: 'none',
            display: 'flex',
            flexDirection: 'column',
            gap: 'var(--token-space-1)',
          }}
        >
          {allErrors.map((msg, i) => (
            <li
              key={i}
              style={{
                fontSize: 'var(--token-fs-13)',
                color: 'var(--token-danger-emphasis)',
                display: 'flex',
                alignItems: 'center',
                gap: 'var(--token-space-1)',
              }}
            >
              <span aria-hidden="true">✕</span>
              {msg}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
