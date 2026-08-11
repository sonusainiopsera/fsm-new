/**
 * @fileoverview Chip — priority, state and risk variants.
 * Color + text + distinct icon/shape so meaning survives greyscale (BR-34).
 */

/** @typedef {'critical' | 'high' | 'medium' | 'low'} PriorityValue */
/** @typedef {'new' | 'assigned' | 'en_route' | 'in_progress' | 'on_hold' | 'completed' | 'closed' | 'cancelled'} StateValue */
/** @typedef {'high' | 'medium' | 'low'} RiskValue */
/** @typedef {'priority' | 'state' | 'risk'} ChipKind */

const PRIORITY_META = {
  critical: { label: 'Critical', icon: '▲', color: 'var(--token-danger-subtle)', textColor: 'var(--token-danger-emphasis)', borderColor: 'var(--token-danger-default)' },
  high:     { label: 'High',     icon: '△', color: 'var(--token-warning-subtle)', textColor: 'var(--token-warning-emphasis)', borderColor: 'var(--token-warning-default)' },
  medium:   { label: 'Medium',   icon: '◇', color: 'var(--token-info-subtle)',    textColor: 'var(--token-info-emphasis)',    borderColor: 'var(--token-info-default)' },
  low:      { label: 'Low',      icon: '○', color: 'var(--token-neutral-100)',    textColor: 'var(--token-text-secondary)',   borderColor: 'var(--token-border-default)' },
}

const STATE_META = {
  new:         { label: 'New',         icon: '◉', color: 'var(--token-neutral-100)',    textColor: 'var(--token-text-secondary)',   borderColor: 'var(--token-border-default)' },
  assigned:    { label: 'Assigned',    icon: '⬡', color: 'var(--token-info-subtle)',    textColor: 'var(--token-info-emphasis)',    borderColor: 'var(--token-info-default)' },
  en_route:    { label: 'En Route',    icon: '➜', color: 'var(--token-accent-50)',      textColor: 'var(--token-accent-700)',       borderColor: 'var(--token-accent-400)' },
  in_progress: { label: 'In Progress', icon: '⟳', color: 'var(--token-success-subtle)', textColor: 'var(--token-success-emphasis)', borderColor: 'var(--token-success-default)' },
  on_hold:     { label: 'On Hold',     icon: '⏸', color: 'var(--token-warning-subtle)', textColor: 'var(--token-warning-emphasis)', borderColor: 'var(--token-warning-default)' },
  completed:   { label: 'Completed',   icon: '✓', color: 'var(--token-success-subtle)', textColor: 'var(--token-success-emphasis)', borderColor: 'var(--token-success-default)' },
  closed:      { label: 'Closed',      icon: '⬜', color: 'var(--token-neutral-100)',    textColor: 'var(--token-text-disabled)',    borderColor: 'var(--token-border-default)' },
  cancelled:   { label: 'Cancelled',   icon: '✕', color: 'var(--token-neutral-100)',    textColor: 'var(--token-text-disabled)',    borderColor: 'var(--token-border-default)' },
}

const RISK_META = {
  high:   { label: 'High Risk',   icon: '⚠', color: 'var(--token-danger-subtle)',  textColor: 'var(--token-danger-emphasis)',  borderColor: 'var(--token-danger-default)' },
  medium: { label: 'Medium Risk', icon: '◈', color: 'var(--token-warning-subtle)', textColor: 'var(--token-warning-emphasis)', borderColor: 'var(--token-warning-default)' },
  low:    { label: 'Low Risk',    icon: '◯', color: 'var(--token-neutral-100)',    textColor: 'var(--token-text-secondary)',   borderColor: 'var(--token-border-default)' },
}

const META_MAP = { priority: PRIORITY_META, state: STATE_META, risk: RISK_META }

/**
 * @param {{
 *   kind: ChipKind,
 *   value: string,
 *   size?: 'sm' | 'md'
 * }} props
 */
export function Chip({ kind, value, size = 'md' }) {
  const metaForKind = META_MAP[kind]
  let meta = metaForKind?.[value]

  if (!meta) {
    if (typeof console !== 'undefined') {
      console.warn(`[Chip] Unknown ${kind} value "${value}". Rendering neutral fallback.`)
    }
    meta = {
      label: value,
      icon: '?',
      color: 'var(--token-neutral-100)',
      textColor: 'var(--token-text-secondary)',
      borderColor: 'var(--token-border-default)',
    }
  }

  return (
    <span
      role="status"
      aria-label={`${kind}: ${meta.label}`}
      data-kind={kind}
      data-value={value}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 'var(--token-space-1)',
        padding: size === 'sm'
          ? 'var(--token-space-1) var(--token-space-2)'
          : 'calc(var(--token-space-1) * 1.5) var(--token-space-3)',
        fontSize: size === 'sm' ? 'var(--token-fs-12)' : 'var(--token-fs-13)',
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
    </span>
  )
}
