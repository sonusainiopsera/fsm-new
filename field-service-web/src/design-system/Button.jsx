/**
 * @typedef {'primary' | 'secondary' | 'ghost' | 'destructive'} ButtonVariant
 * @typedef {'sm' | 'md' | 'lg'} ButtonSize
 */

/**
 * @param {{
 *   variant?: ButtonVariant,
 *   size?: ButtonSize,
 *   loading?: boolean,
 *   disabled?: boolean,
 *   onClick?: (e: React.MouseEvent<HTMLButtonElement>) => void,
 *   type?: 'button' | 'submit' | 'reset',
 *   children: React.ReactNode,
 *   className?: string,
 *   'aria-label'?: string,
 * }} props
 */
export function Button({
  variant = 'primary',
  size = 'md',
  loading = false,
  disabled = false,
  onClick,
  type = 'button',
  children,
  className,
  'aria-label': ariaLabel,
}) {
  const isDisabled = disabled || loading

  return (
    <button
      type={type}
      className={className}
      disabled={isDisabled}
      aria-disabled={isDisabled}
      aria-busy={loading}
      aria-label={ariaLabel}
      onClick={onClick}
      style={{
        ...styles.base,
        ...sizeStyles[size],
        ...variantStyles[variant],
        ...(isDisabled ? styles.disabled : {}),
      }}
    >
      {loading && (
        <span aria-hidden="true" style={styles.spinner}>
          <svg
            width="1em"
            height="1em"
            viewBox="0 0 16 16"
            fill="none"
            style={{ animation: 'button-spin 0.7s linear infinite' }}
          >
            <circle
              cx="8"
              cy="8"
              r="6"
              stroke="currentColor"
              strokeWidth="2"
              strokeOpacity="0.3"
            />
            <path
              d="M8 2a6 6 0 0 1 6 6"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
            />
          </svg>
        </span>
      )}
      {children}
    </button>
  )
}

/** @type {React.CSSProperties} */
const baseStyle = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  gap: 'var(--space-2)',
  borderRadius: 'var(--radius-md)',
  fontWeight: 'var(--font-weight-medium)',
  lineHeight: 1,
  cursor: 'pointer',
  border: '1px solid transparent',
  transition: `background-color var(--motion-duration-fast) var(--motion-easing), color var(--motion-duration-fast) var(--motion-easing), border-color var(--motion-duration-fast) var(--motion-easing)`,
  outline: 'none',
  textDecoration: 'none',
  whiteSpace: 'nowrap',
  userSelect: 'none',
}

/** @type {Record<string, React.CSSProperties>} */
const styles = {
  base: baseStyle,
  spinner: {
    display: 'inline-flex',
    alignItems: 'center',
    fontSize: '1em',
  },
  disabled: {
    opacity: 0.5,
    cursor: 'not-allowed',
    pointerEvents: 'none',
  },
}

/** @type {Record<ButtonSize, React.CSSProperties>} */
const sizeStyles = {
  sm: {
    paddingInline: 'var(--space-3)',
    paddingBlock: 'var(--space-2)',
    fontSize: 'var(--font-size-sm)',
    minHeight: 'var(--touch-target)',
  },
  md: {
    paddingInline: 'var(--space-4)',
    paddingBlock: 'var(--space-3)',
    fontSize: 'var(--font-size-base)',
    minHeight: 'var(--touch-target)',
  },
  lg: {
    paddingInline: 'var(--space-6)',
    paddingBlock: 'var(--space-4)',
    fontSize: 'var(--font-size-lg)',
    minHeight: 'var(--touch-target)',
  },
}

/** @type {Record<ButtonVariant, React.CSSProperties>} */
const variantStyles = {
  primary: {
    backgroundColor: 'var(--color-primary)',
    color: 'var(--color-text-on-primary)',
    borderColor: 'var(--color-primary)',
  },
  secondary: {
    backgroundColor: 'var(--color-bg-muted)',
    color: 'var(--color-text-primary)',
    borderColor: 'var(--color-border)',
  },
  ghost: {
    backgroundColor: 'transparent',
    color: 'var(--color-text-primary)',
    borderColor: 'transparent',
  },
  destructive: {
    backgroundColor: 'var(--color-error)',
    color: 'var(--color-text-on-primary)',
    borderColor: 'var(--color-error)',
  },
}

// Inject focus-visible and hover styles
if (typeof document !== 'undefined') {
  const styleId = 'button-focus-style'
  if (!document.getElementById(styleId)) {
    const style = document.createElement('style')
    style.id = styleId
    style.textContent = `
      @keyframes button-spin {
        from { transform: rotate(0deg); }
        to { transform: rotate(360deg); }
      }
      button:focus-visible {
        outline: none;
        box-shadow: var(--focus-ring) !important;
      }
    `
    document.head.appendChild(style)
  }
}
