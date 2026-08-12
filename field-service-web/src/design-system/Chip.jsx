/**
 * @typedef {'default' | 'success' | 'warning' | 'error' | 'info'} ChipVariant
 */

/**
 * @param {{
 *   variant?: ChipVariant,
 *   children: React.ReactNode,
 *   className?: string,
 * }} props
 */
export function Chip({ variant = 'default', children, className }) {
  /** @type {React.CSSProperties} */
  const variantStyle = variantStyles[variant]

  return (
    <span
      className={className}
      style={{
        ...styles.base,
        ...variantStyle,
      }}
    >
      {children}
    </span>
  )
}

/** @type {React.CSSProperties} */
const baseStyle = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 'var(--space-1)',
  paddingInline: 'var(--space-2)',
  paddingBlock: 'var(--space-1)',
  borderRadius: 'var(--radius-full)',
  fontSize: 'var(--font-size-xs)',
  fontWeight: 'var(--font-weight-medium)',
  lineHeight: 1.4,
  whiteSpace: 'nowrap',
  border: '1px solid transparent',
}

/** @type {Record<string, React.CSSProperties>} */
const styles = {
  base: baseStyle,
}

/** @type {Record<ChipVariant, React.CSSProperties>} */
const variantStyles = {
  default: {
    backgroundColor: 'var(--color-bg-muted)',
    color: 'var(--color-text-secondary)',
    borderColor: 'var(--color-border)',
  },
  success: {
    backgroundColor: 'color-mix(in srgb, var(--color-success) 12%, var(--color-bg-base))',
    color: 'var(--color-success)',
    borderColor: 'color-mix(in srgb, var(--color-success) 30%, var(--color-bg-base))',
  },
  warning: {
    backgroundColor: 'color-mix(in srgb, var(--color-warning) 12%, var(--color-bg-base))',
    color: 'var(--color-warning)',
    borderColor: 'color-mix(in srgb, var(--color-warning) 30%, var(--color-bg-base))',
  },
  error: {
    backgroundColor: 'color-mix(in srgb, var(--color-error) 12%, var(--color-bg-base))',
    color: 'var(--color-error)',
    borderColor: 'color-mix(in srgb, var(--color-error) 30%, var(--color-bg-base))',
  },
  info: {
    backgroundColor: 'color-mix(in srgb, var(--color-info) 12%, var(--color-bg-base))',
    color: 'var(--color-info)',
    borderColor: 'color-mix(in srgb, var(--color-info) 30%, var(--color-bg-base))',
  },
}
