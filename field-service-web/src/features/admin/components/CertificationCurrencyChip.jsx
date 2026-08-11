/**
 * @fileoverview Certification currency status chip.
 *
 * Renders three states (current, expiring-soon, expired) driven exclusively
 * by the API's `current` and `daysUntilExpiry` fields. The client NEVER
 * recomputes currency — it renders only what the API returns (AC-5).
 *
 * Status is conveyed by icon + text + colour so meaning never depends on
 * colour alone (BR-34).
 *
 * @module features/admin/components/CertificationCurrencyChip
 */

/** @typedef {'current' | 'expiring-soon' | 'expired'} CurrencyStatus */

const EXPIRING_SOON_DAYS = 30

/**
 * Derives the display status from API-provided values.
 * Does NOT recompute from dates — trusts the API's `current` flag.
 *
 * @param {{ current: boolean, daysUntilExpiry: number | null }} cert
 * @returns {CurrencyStatus}
 */
export function deriveCurrencyStatus({ current, daysUntilExpiry }) {
  if (!current) return 'expired'
  if (daysUntilExpiry !== null && daysUntilExpiry <= EXPIRING_SOON_DAYS) return 'expiring-soon'
  return 'current'
}

const STATUS_META = {
  current: {
    icon: '✓',
    label: 'Current',
    color: 'var(--token-success-subtle)',
    textColor: 'var(--token-success-emphasis)',
    borderColor: 'var(--token-success-default)',
    ariaLabel: 'Certification current',
  },
  'expiring-soon': {
    icon: '⚠',
    label: 'Expiring soon',
    color: 'var(--token-warning-subtle)',
    textColor: 'var(--token-warning-emphasis)',
    borderColor: 'var(--token-warning-default)',
    ariaLabel: 'Certification expiring soon',
  },
  expired: {
    icon: '✕',
    label: 'Expired',
    color: 'var(--token-danger-subtle)',
    textColor: 'var(--token-danger-emphasis)',
    borderColor: 'var(--token-danger-default)',
    ariaLabel: 'Certification expired',
  },
}

/**
 * @param {{
 *   current: boolean,
 *   daysUntilExpiry: number | null,
 *   expiresOn?: string | null
 * }} props
 */
export function CertificationCurrencyChip({ current, daysUntilExpiry, expiresOn }) {
  const status = deriveCurrencyStatus({ current, daysUntilExpiry })
  const meta = STATUS_META[status]

  const expiryNote = daysUntilExpiry !== null
    ? status === 'expired'
      ? `Expired ${Math.abs(daysUntilExpiry)} day${Math.abs(daysUntilExpiry) === 1 ? '' : 's'} ago`
      : daysUntilExpiry === 0
        ? 'Expires today'
        : `Expires in ${daysUntilExpiry} day${daysUntilExpiry === 1 ? '' : 's'}`
    : null

  const ariaLabel = expiryNote ? `${meta.ariaLabel}. ${expiryNote}` : meta.ariaLabel

  return (
    <span
      data-component="cert-currency-chip"
      data-status={status}
      role="status"
      aria-label={ariaLabel}
      title={expiresOn ? `Expires: ${expiresOn}` : undefined}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 'var(--token-space-1)',
        padding: 'calc(var(--token-space-1) * 1.5) var(--token-space-3)',
        fontSize: 'var(--token-fs-13)',
        fontFamily: 'var(--token-family-base)',
        borderRadius: 'var(--token-radius-pill)',
        background: meta.color,
        color: meta.textColor,
        border: `1px solid ${meta.borderColor}`,
        whiteSpace: 'nowrap',
        fontWeight: 500,
      }}
    >
      <span aria-hidden="true" style={{ fontSize: '0.85em', lineHeight: 1 }}>{meta.icon}</span>
      <span>{meta.label}</span>
      {expiryNote && (
        <span style={{ fontSize: 'var(--token-fs-12)', fontWeight: 400, opacity: 0.8 }}>
          {' '}({expiryNote})
        </span>
      )}
    </span>
  )
}
