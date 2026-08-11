/**
 * @fileoverview FilterBar — work order board filter controls.
 *
 * AC-2: State multi-select, priority, technician, customer, date range, at-risk toggle.
 * Filter state is reflected in URL query params (managed by parent via callbacks).
 * Changes are debounced 300 ms to prevent request storms on rapid input.
 *
 * Accessibility: all controls are labelled; the filter section is a landmark <search>.
 */
import { useState, useEffect, useRef } from 'react'

const STATES = [
  { value: 'NEW', label: 'New' },
  { value: 'ASSIGNED', label: 'Assigned' },
  { value: 'EN_ROUTE', label: 'En Route' },
  { value: 'IN_PROGRESS', label: 'In Progress' },
  { value: 'ON_HOLD', label: 'On Hold' },
  { value: 'COMPLETED', label: 'Completed' },
]

const PRIORITIES = [
  { value: 'CRITICAL', label: 'Critical' },
  { value: 'HIGH', label: 'High' },
  { value: 'MEDIUM', label: 'Medium' },
  { value: 'LOW', label: 'Low' },
]

/**
 * @typedef {{
 *   states?: string[],
 *   priorities?: string[],
 *   technicianId?: string,
 *   customerId?: string,
 *   atRisk?: boolean,
 *   dateFrom?: string,
 *   dateTo?: string
 * }} FilterValues
 */

/**
 * @param {{
 *   filters: FilterValues,
 *   onChange: (filters: FilterValues) => void
 * }} props
 */
export function FilterBar({ filters, onChange }) {
  const [local, setLocal] = useState(filters)
  const debounceRef = useRef(/** @type {ReturnType<typeof setTimeout> | null} */ (null))

  // Sync local state if parent filters change (e.g. URL navigation)
  useEffect(() => {
    setLocal(filters)
  }, [filters])

  function apply(updates) {
    const next = { ...local, ...updates }
    setLocal(next)
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(() => onChange(next), 300)
  }

  function toggleMulti(field, value) {
    const current = local[field] ?? []
    const next = current.includes(value)
      ? current.filter(v => v !== value)
      : [...current, value]
    apply({ [field]: next.length ? next : undefined })
  }

  function handleReset() {
    const empty = {}
    setLocal(empty)
    if (debounceRef.current) clearTimeout(debounceRef.current)
    onChange(empty)
  }

  const hasFilters = Object.values(local).some(v =>
    v !== undefined && v !== false && !(Array.isArray(v) && v.length === 0)
  )

  return (
    <search
      aria-label="Filter work orders"
      style={{
        display: 'flex',
        flexWrap: 'wrap',
        gap: 'var(--token-space-3)',
        alignItems: 'flex-end',
        padding: 'var(--token-space-3) var(--token-space-4)',
        background: 'var(--token-surface-card)',
        borderBottom: 'var(--token-elevation-border)',
      }}
    >
      {/* State multi-select */}
      <fieldset
        style={{ border: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: 'var(--token-space-1)' }}
      >
        <legend style={{ fontSize: 'var(--token-fs-12)', color: 'var(--token-text-secondary)', fontWeight: 500, padding: 0, marginBottom: 'var(--token-space-1)' }}>
          State
        </legend>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--token-space-2)' }}>
          {STATES.map(s => {
            const checked = (local.states ?? []).includes(s.value)
            return (
              <label
                key={s.value}
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 'var(--token-space-1)',
                  fontSize: 'var(--token-fs-13)',
                  cursor: 'pointer',
                  padding: 'var(--token-space-1) var(--token-space-2)',
                  background: checked ? 'var(--token-accent-50)' : 'transparent',
                  border: `1px solid ${checked ? 'var(--token-accent-400)' : 'var(--token-border-default)'}`,
                  borderRadius: 'var(--token-radius-sm)',
                  color: checked ? 'var(--token-accent-700)' : 'var(--token-text-primary)',
                }}
              >
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => toggleMulti('states', s.value)}
                  style={{ srOnly: true, position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0,0,0,0)' }}
                  aria-label={`Filter by state: ${s.label}`}
                />
                {s.label}
              </label>
            )
          })}
        </div>
      </fieldset>

      {/* Priority multi-select */}
      <fieldset
        style={{ border: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', gap: 'var(--token-space-1)' }}
      >
        <legend style={{ fontSize: 'var(--token-fs-12)', color: 'var(--token-text-secondary)', fontWeight: 500, padding: 0, marginBottom: 'var(--token-space-1)' }}>
          Priority
        </legend>
        <div style={{ display: 'flex', gap: 'var(--token-space-2)' }}>
          {PRIORITIES.map(p => {
            const checked = (local.priorities ?? []).includes(p.value)
            return (
              <label
                key={p.value}
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 'var(--token-space-1)',
                  fontSize: 'var(--token-fs-13)',
                  cursor: 'pointer',
                  padding: 'var(--token-space-1) var(--token-space-2)',
                  background: checked ? 'var(--token-accent-50)' : 'transparent',
                  border: `1px solid ${checked ? 'var(--token-accent-400)' : 'var(--token-border-default)'}`,
                  borderRadius: 'var(--token-radius-sm)',
                  color: checked ? 'var(--token-accent-700)' : 'var(--token-text-primary)',
                }}
              >
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => toggleMulti('priorities', p.value)}
                  style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0,0,0,0)' }}
                  aria-label={`Filter by priority: ${p.label}`}
                />
                {p.label}
              </label>
            )
          })}
        </div>
      </fieldset>

      {/* At-risk toggle */}
      <label
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 'var(--token-space-2)',
          fontSize: 'var(--token-fs-13)',
          cursor: 'pointer',
        }}
      >
        <input
          type="checkbox"
          checked={!!local.atRisk}
          onChange={e => apply({ atRisk: e.target.checked || undefined })}
          aria-label="Show at-risk work orders only"
        />
        <span>⚠ At-risk only</span>
      </label>

      {/* Date range — from */}
      <label style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-1)', fontSize: 'var(--token-fs-12)', color: 'var(--token-text-secondary)' }}>
        Due from
        <input
          type="date"
          value={local.dateFrom ?? ''}
          onChange={e => apply({ dateFrom: e.target.value || undefined })}
          aria-label="Filter by due date from"
          style={{ fontSize: 'var(--token-fs-13)', padding: 'var(--token-space-1) var(--token-space-2)', border: '1px solid var(--token-border-default)', borderRadius: 'var(--token-radius-sm)', background: 'var(--token-surface-base)' }}
        />
      </label>
      <label style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-1)', fontSize: 'var(--token-fs-12)', color: 'var(--token-text-secondary)' }}>
        Due to
        <input
          type="date"
          value={local.dateTo ?? ''}
          onChange={e => apply({ dateTo: e.target.value || undefined })}
          aria-label="Filter by due date to"
          style={{ fontSize: 'var(--token-fs-13)', padding: 'var(--token-space-1) var(--token-space-2)', border: '1px solid var(--token-border-default)', borderRadius: 'var(--token-radius-sm)', background: 'var(--token-surface-base)' }}
        />
      </label>

      {hasFilters && (
        <button
          type="button"
          onClick={handleReset}
          aria-label="Clear all filters"
          style={{
            fontSize: 'var(--token-fs-13)',
            color: 'var(--token-accent-600)',
            background: 'none',
            border: 'none',
            cursor: 'pointer',
            textDecoration: 'underline',
            padding: 'var(--token-space-1)',
          }}
        >
          Clear filters
        </button>
      )}
    </search>
  )
}
