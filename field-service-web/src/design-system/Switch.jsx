import { useId } from 'react'

/**
 * @param {{
 *   id?: string,
 *   label: string,
 *   description?: string,
 *   checked: boolean,
 *   onChange: (checked: boolean) => void,
 *   disabled?: boolean
 * }} props
 */
export function Switch({ id: providedId, label, description, checked, onChange, disabled = false }) {
  const generatedId = useId()
  const id = providedId ?? generatedId
  const descriptionId = description ? `${id}-description` : undefined

  /** @param {React.KeyboardEvent<HTMLButtonElement>} e */
  function handleKeyDown(e) {
    if (e.key === ' ' || e.key === 'Enter') {
      e.preventDefault()
      if (!disabled) {
        onChange(!checked)
      }
    }
  }

  return (
    <div style={styles.container}>
      <div style={styles.labelGroup}>
        <label
          htmlFor={id}
          style={{
            ...styles.label,
            color: disabled ? 'var(--color-text-disabled)' : 'var(--color-text-primary)',
            cursor: disabled ? 'not-allowed' : 'pointer',
          }}
        >
          {label}
        </label>
        {description && (
          <p id={descriptionId} style={styles.description}>
            {description}
          </p>
        )}
      </div>
      <button
        id={id}
        role="switch"
        aria-checked={checked}
        aria-label={label}
        aria-describedby={descriptionId}
        disabled={disabled}
        onClick={() => !disabled && onChange(!checked)}
        onKeyDown={handleKeyDown}
        style={{
          ...styles.track,
          backgroundColor: checked
            ? 'var(--color-status-enabled)'
            : 'var(--color-status-disabled)',
          opacity: disabled ? 0.5 : 1,
          cursor: disabled ? 'not-allowed' : 'pointer',
        }}
      >
        <span
          aria-hidden="true"
          style={{
            ...styles.thumb,
            transform: checked ? 'translateX(1.25rem)' : 'translateX(0.125rem)',
          }}
        />
        <span style={styles.srOnly}>{checked ? 'On' : 'Off'}</span>
      </button>
    </div>
  )
}

/** @type {Record<string, React.CSSProperties>} */
const styles = {
  container: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 'var(--space-4)',
    minHeight: 'var(--touch-target)',
  },
  labelGroup: {
    display: 'flex',
    flexDirection: 'column',
    gap: 'var(--space-1)',
    flex: 1,
  },
  label: {
    fontSize: 'var(--font-size-sm)',
    fontWeight: 'var(--font-weight-medium)',
    lineHeight: 1.4,
  },
  description: {
    margin: 0,
    fontSize: 'var(--font-size-xs)',
    color: 'var(--color-text-secondary)',
    lineHeight: 1.4,
  },
  track: {
    position: 'relative',
    display: 'inline-flex',
    alignItems: 'center',
    flexShrink: 0,
    width: '2.75rem',
    height: '1.5rem',
    borderRadius: 'var(--radius-full)',
    border: 'none',
    padding: 0,
    transition: `background-color var(--motion-duration-fast) var(--motion-easing)`,
    // Ensure at least 44px touch target via padding trick
    minWidth: 'var(--touch-target)',
    minHeight: 'var(--touch-target)',
    // Reset button styles
    outline: 'none',
    WebkitAppearance: 'none',
  },
  thumb: {
    display: 'block',
    width: '1.25rem',
    height: '1.25rem',
    borderRadius: 'var(--radius-full)',
    backgroundColor: 'var(--color-text-on-primary)',
    boxShadow: 'var(--shadow-sm)',
    transition: `transform var(--motion-duration-fast) var(--motion-easing)`,
    pointerEvents: 'none',
  },
  srOnly: {
    position: 'absolute',
    width: '1px',
    height: '1px',
    padding: 0,
    margin: '-1px',
    overflow: 'hidden',
    clip: 'rect(0,0,0,0)',
    whiteSpace: 'nowrap',
    border: 0,
  },
}

// Inject focus-visible ring via a <style> tag approach would require SSR concerns.
// Instead, we use a global CSS approach. See tokens.css for the focus-visible rule.
// We apply it inline here using :focus-visible pseudo via a CSS class.
// Since inline styles can't target pseudo-classes, we append a style element once.
if (typeof document !== 'undefined') {
  const styleId = 'switch-focus-style'
  if (!document.getElementById(styleId)) {
    const style = document.createElement('style')
    style.id = styleId
    style.textContent = `
      [role="switch"]:focus-visible {
        outline: none;
        box-shadow: var(--focus-ring) !important;
      }
    `
    document.head.appendChild(style)
  }
}
