/**
 * @fileoverview WindowSelector — tab strip for selecting the analytics window.
 *
 * Syncs the selected window to the URL via useSearchParams so managers can
 * share or bookmark a specific view.  Changing the window key changes the
 * TanStack Query queryKey, automatically cancelling any in-flight request for
 * the previous key.
 */

import { useSearchParams } from 'react-router-dom'

/** @typedef {'SEVEN_DAYS' | 'THIRTY_DAYS' | 'NINETY_DAYS'} WindowKey */

/** @type {Array<{ key: WindowKey, label: string, shortLabel: string }>} */
export const WINDOW_OPTIONS = [
  { key: 'SEVEN_DAYS',  label: 'Last 7 days',  shortLabel: '7d'  },
  { key: 'THIRTY_DAYS', label: 'Last 30 days', shortLabel: '30d' },
  { key: 'NINETY_DAYS', label: 'Last 90 days', shortLabel: '90d' },
]

export const DEFAULT_WINDOW = /** @type {WindowKey} */ ('THIRTY_DAYS')

const VALID_WINDOWS = new Set(WINDOW_OPTIONS.map(o => o.key))

/**
 * Reads and writes the `window` search param.
 *
 * @returns {{ window: WindowKey, setWindow: (w: WindowKey) => void }}
 */
export function useWindowParam() {
  const [params, setParams] = useSearchParams()
  const raw = params.get('window')
  const window = VALID_WINDOWS.has(/** @type {WindowKey} */(raw ?? ''))
    ? /** @type {WindowKey} */ (raw)
    : DEFAULT_WINDOW

  function setWindow(/** @type {WindowKey} */ key) {
    setParams(prev => {
      const next = new URLSearchParams(prev)
      next.set('window', key)
      return next
    }, { replace: true })
  }

  return { window, setWindow }
}

/**
 * Reads and writes the `segment` search param.
 *
 * @returns {{ segment: string, setSegment: (s: string) => void }}
 */
export function useSegmentParam() {
  const [params, setParams] = useSearchParams()
  const segment = params.get('segment') ?? 'ALL'

  function setSegment(/** @type {string} */ key) {
    setParams(prev => {
      const next = new URLSearchParams(prev)
      next.set('segment', key)
      return next
    }, { replace: true })
  }

  return { segment, setSegment }
}

/** @type {Array<{ key: string, label: string }>} */
export const SEGMENT_OPTIONS = [
  { key: 'ALL',      label: 'All' },
  { key: 'PRIORITY', label: 'Priority' },
  { key: 'TEAM',     label: 'Team' },
]

/**
 * @param {{ segment: string, onChange: (s: string) => void }} props
 */
export function SegmentFilter({ segment, onChange }) {
  return (
    <div
      style={{ display: 'flex', alignItems: 'center', gap: 'var(--token-space-2)' }}
      aria-label="Segment filter"
    >
      <label
        htmlFor="segment-select"
        style={{
          fontSize: 'var(--token-fs-13)',
          color: 'var(--token-text-secondary)',
          fontFamily: 'var(--token-family-base)',
          whiteSpace: 'nowrap',
        }}
      >
        Segment:
      </label>
      <select
        id="segment-select"
        value={segment}
        onChange={e => onChange(e.target.value)}
        style={{
          fontSize: 'var(--token-fs-13)',
          fontFamily: 'var(--token-family-base)',
          padding: 'var(--token-space-1) var(--token-space-2)',
          borderRadius: 'var(--token-radius-control)',
          border: '1px solid var(--token-border-default)',
          background: 'var(--token-surface-card)',
          color: 'var(--token-text-primary)',
          cursor: 'pointer',
        }}
      >
        {SEGMENT_OPTIONS.map(({ key, label }) => (
          <option key={key} value={key}>{label}</option>
        ))}
      </select>
    </div>
  )
}

/**
 * @param {{
 *   window: WindowKey,
 *   onChange: (w: WindowKey) => void
 * }} props
 */
export function WindowSelector({ window, onChange }) {
  return (
    <div
      role="tablist"
      aria-label="Analytics time window"
      data-component="window-selector"
      style={{
        display: 'inline-flex',
        gap: 0,
        borderRadius: 'var(--token-radius-control)',
        border: '1px solid var(--token-border-default)',
        overflow: 'hidden',
        background: 'var(--token-surface-base)',
      }}
    >
      {WINDOW_OPTIONS.map(({ key, label, shortLabel }) => {
        const selected = key === window
        return (
          <button
            key={key}
            role="tab"
            aria-selected={selected}
            aria-label={label}
            type="button"
            onClick={() => onChange(key)}
            style={{
              padding: 'var(--token-space-2) var(--token-space-4)',
              fontSize: 'var(--token-fs-13)',
              fontFamily: 'var(--token-family-base)',
              fontWeight: selected ? 600 : 400,
              color: selected ? 'var(--token-accent-700)' : 'var(--token-text-primary)',
              background: selected ? 'var(--token-accent-100)' : 'transparent',
              border: 'none',
              borderRight: '1px solid var(--token-border-default)',
              cursor: 'pointer',
              outline: 'none',
              transition: 'background var(--token-duration-enter) var(--token-easing-standard)',
            }}
            onFocus={e => {
              e.currentTarget.style.boxShadow = 'inset 0 0 0 2px var(--token-accent-400)'
            }}
            onBlur={e => {
              e.currentTarget.style.boxShadow = 'none'
            }}
          >
            <span aria-hidden="true">{shortLabel}</span>
          </button>
        )
      })}
    </div>
  )
}
