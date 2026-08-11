/**
 * @fileoverview Button primitive — five variants, token-only styling, 44 px touch target.
 */

/** @typedef {'primary' | 'secondary' | 'tertiary' | 'ghost' | 'destructive'} ButtonVariant */

const VALID_VARIANTS = /** @type {ButtonVariant[]} */ (['primary', 'secondary', 'tertiary', 'ghost', 'destructive'])

/**
 * @param {{
 *   variant?: ButtonVariant,
 *   children: React.ReactNode,
 *   onClick?: React.MouseEventHandler<HTMLButtonElement>,
 *   disabled?: boolean,
 *   type?: 'button' | 'submit' | 'reset',
 *   touchTarget?: boolean,
 *   'aria-label'?: string,
 *   [k: string]: unknown
 * }} props
 */
export function Button({
  variant = 'primary',
  children,
  onClick,
  disabled = false,
  type = 'button',
  touchTarget = false,
  ...rest
}) {
  if (!VALID_VARIANTS.includes(variant)) {
    if (typeof console !== 'undefined') {
      console.warn(`[Button] Unknown variant "${variant}". Expected one of: ${VALID_VARIANTS.join(', ')}`)
    }
  }

  return (
    <button
      // eslint-disable-next-line react/button-has-type
      type={type}
      data-variant={variant}
      data-touch-target={touchTarget ? 'true' : undefined}
      disabled={disabled}
      onClick={onClick}
      style={{
        fontFamily: 'var(--token-family-base)',
        fontSize: 'var(--token-fs-14)',
        borderRadius: 'var(--token-radius-control)',
        cursor: disabled ? 'not-allowed' : 'pointer',
        minHeight: touchTarget ? '44px' : undefined,
        minWidth: touchTarget ? '44px' : undefined,
      }}
      {...rest}
    >
      {children}
    </button>
  )
}
