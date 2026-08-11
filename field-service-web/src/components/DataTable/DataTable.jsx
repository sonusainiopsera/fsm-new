/**
 * @fileoverview DataTable — headless column definitions, sticky header, density-aware rows,
 * sort affordance, responsive stacked cards below 768 px. No zebra striping.
 */
import { useState, useRef } from 'react'
import { useDensity } from '../../density/DensityContext.js'
import { useResponsiveTableMode } from './useResponsiveTableMode.js'
import { EmptyState } from '../StateSurface/StateSurface.jsx'

/**
 * @typedef {{
 *   key: string,
 *   header: string,
 *   align?: 'left' | 'right' | 'center',
 *   sortable?: boolean,
 *   numeric?: boolean,
 *   render?: (value: unknown, row: Record<string, unknown>) => React.ReactNode
 * }} ColumnDef
 */

/**
 * @typedef {'asc' | 'desc'} SortDir
 * @typedef {{ key: string, dir: SortDir }} SortState
 */

/**
 * @param {{
 *   columns: ColumnDef[],
 *   rows: Record<string, unknown>[],
 *   rowKey: (row: Record<string, unknown>) => string,
 *   selectedRowKey?: string | null,
 *   onRowClick?: (row: Record<string, unknown>) => void,
 *   caption?: string,
 *   'aria-label'?: string,
 *   actions?: (row: Record<string, unknown>) => React.ReactNode
 * }} props
 */
export function DataTable({ columns, rows, rowKey, selectedRowKey, onRowClick, caption, actions, ...rest }) {
  const { density } = useDensity()
  const [sort, setSort] = useState(/** @type {SortState | null} */ (null))
  const containerRef = useRef(null)
  const { isStacked } = useResponsiveTableMode(containerRef)

  const rowHeight = density === 'compact' ? '32px' : '40px'

  function toggleSort(key) {
    setSort(prev => {
      if (!prev || prev.key !== key) return { key, dir: 'asc' }
      if (prev.dir === 'asc') return { key, dir: 'desc' }
      return null
    })
  }

  const sortedRows = sort
    ? [...rows].sort((a, b) => {
        const av = a[sort.key]
        const bv = b[sort.key]
        const cmp = av < bv ? -1 : av > bv ? 1 : 0
        return sort.dir === 'asc' ? cmp : -cmp
      })
    : rows

  if (isStacked) {
    return (
      <div
        ref={containerRef}
        role="list"
        aria-label={rest['aria-label'] ?? caption ?? 'Data list'}
        style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-3)' }}
      >
        {sortedRows.length === 0 ? (
          <EmptyState />
        ) : (
          sortedRows.map(row => {
            const key = rowKey(row)
            const isSelected = key === selectedRowKey
            return (
              <div
                key={key}
                role="listitem"
                data-selected={isSelected ? 'true' : undefined}
                onClick={() => onRowClick?.(row)}
                style={{
                  background: 'var(--token-surface-card)',
                  border: 'var(--token-elevation-border)',
                  borderRadius: 'var(--token-radius-card)',
                  padding: 'var(--token-space-4)',
                  cursor: onRowClick ? 'pointer' : undefined,
                  borderLeft: isSelected ? '2px solid var(--token-accent-500)' : undefined,
                }}
              >
                <dl style={{ margin: 0 }}>
                  {columns.map(col => (
                    <div key={col.key} style={{ display: 'flex', justifyContent: 'space-between', gap: 'var(--token-space-4)', padding: 'var(--token-space-1) 0', borderBottom: 'var(--token-elevation-border)' }}>
                      <dt style={{ fontSize: 'var(--token-fs-12)', color: 'var(--token-text-secondary)', fontWeight: 500 }}>{col.header}</dt>
                      <dd style={{ margin: 0, fontSize: 'var(--token-fs-14)', fontVariantNumeric: col.numeric ? 'var(--token-numeric)' : undefined, textAlign: 'right' }}>
                        {col.render ? col.render(row[col.key], row) : String(row[col.key] ?? '')}
                      </dd>
                    </div>
                  ))}
                </dl>
                {actions && <div style={{ marginTop: 'var(--token-space-3)' }}>{actions(row)}</div>}
              </div>
            )
          })
        )}
      </div>
    )
  }

  return (
    <div
      ref={containerRef}
      style={{ overflow: 'auto', maxWidth: '100%' }}
    >
      <table
        role="grid"
        aria-label={rest['aria-label'] ?? caption}
        style={{
          width: '100%',
          borderCollapse: 'collapse',
          fontFamily: 'var(--token-family-base)',
          fontSize: 'var(--token-fs-14)',
          tableLayout: 'auto',
        }}
      >
        {caption && <caption className="sr-only">{caption}</caption>}

        {/* Sticky header */}
        <thead style={{ position: 'sticky', top: 0, zIndex: 1, background: 'var(--token-surface-card)' }}>
          <tr>
            {columns.map(col => {
              const isSorted = sort?.key === col.key
              return (
                <th
                  key={col.key}
                  scope="col"
                  aria-sort={
                    col.sortable
                      ? isSorted ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'
                      : undefined
                  }
                  style={{
                    padding: '0 var(--token-space-4)',
                    height: rowHeight,
                    textAlign: col.align ?? (col.numeric ? 'right' : 'left'),
                    fontWeight: 600,
                    fontSize: 'var(--token-fs-12)',
                    color: 'var(--token-text-secondary)',
                    textTransform: 'uppercase',
                    letterSpacing: '0.05em',
                    borderBottom: 'var(--token-elevation-border)',
                    whiteSpace: 'nowrap',
                    cursor: col.sortable ? 'pointer' : undefined,
                    userSelect: 'none',
                  }}
                  onClick={col.sortable ? () => toggleSort(col.key) : undefined}
                  tabIndex={col.sortable ? 0 : undefined}
                  onKeyDown={col.sortable ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleSort(col.key) } } : undefined}
                >
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 'var(--token-space-1)' }}>
                    {col.header}
                    {col.sortable && (
                      <span
                        aria-hidden="true"
                        style={{
                          color: isSorted ? 'var(--token-accent-600)' : 'var(--token-neutral-300)',
                          fontSize: '0.75em',
                          lineHeight: 1,
                        }}
                      >
                        {isSorted && sort.dir === 'desc' ? '▼' : '▲'}
                      </span>
                    )}
                  </span>
                </th>
              )
            })}
            {actions && <th scope="col" style={{ width: '1%', whiteSpace: 'nowrap', height: rowHeight, padding: '0 var(--token-space-4)', borderBottom: 'var(--token-elevation-border)' }}><span className="sr-only">Actions</span></th>}
          </tr>
        </thead>

        <tbody>
          {sortedRows.length === 0 ? (
            <tr>
              <td colSpan={columns.length + (actions ? 1 : 0)} style={{ padding: 0 }}>
                <EmptyState />
              </td>
            </tr>
          ) : (
            sortedRows.map(row => {
              const key = rowKey(row)
              const isSelected = key === selectedRowKey
              return (
                <tr
                  key={key}
                  aria-selected={isSelected}
                  data-selected={isSelected ? 'true' : undefined}
                  onClick={() => onRowClick?.(row)}
                  style={{
                    height: rowHeight,
                    borderBottom: 'var(--token-elevation-border)',
                    cursor: onRowClick ? 'pointer' : undefined,
                    background: isSelected ? 'var(--token-accent-50)' : undefined,
                    boxShadow: isSelected ? 'inset 2px 0 0 var(--token-accent-500)' : undefined,
                  }}
                  onMouseEnter={e => { if (!isSelected) e.currentTarget.style.background = 'var(--token-neutral-50)' }}
                  onMouseLeave={e => { if (!isSelected) e.currentTarget.style.background = '' }}
                >
                  {columns.map(col => (
                    <td
                      key={col.key}
                      style={{
                        padding: '0 var(--token-space-4)',
                        textAlign: col.align ?? (col.numeric ? 'right' : 'left'),
                        fontVariantNumeric: col.numeric ? 'var(--token-numeric)' : undefined,
                        color: 'var(--token-text-primary)',
                        maxWidth: '300px',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                      title={typeof row[col.key] === 'string' ? row[col.key] : undefined}
                    >
                      {col.render ? col.render(row[col.key], row) : String(row[col.key] ?? '')}
                    </td>
                  ))}
                  {actions && (
                    <td style={{ padding: '0 var(--token-space-4)', textAlign: 'right', whiteSpace: 'nowrap' }}>
                      {actions(row)}
                    </td>
                  )}
                </tr>
              )
            })
          )}
        </tbody>
      </table>
    </div>
  )
}

/**
 * Density toggle control — switches the density context for a table.
 * @param {{ tableId?: string }} props
 */
export function DensityToggle({ tableId }) {
  const { density, setDensity } = useDensity()
  return (
    <div role="group" aria-label="Row density" style={{ display: 'flex', gap: 'var(--token-space-1)' }}>
      {(['comfortable', 'compact']).map(d => (
        <button
          key={d}
          type="button"
          aria-pressed={density === d}
          onClick={() => setDensity(d)}
          style={{
            padding: 'var(--token-space-1) var(--token-space-3)',
            fontSize: 'var(--token-fs-12)',
            fontFamily: 'var(--token-family-base)',
            borderRadius: 'var(--token-radius-control)',
            border: '1px solid var(--token-border-default)',
            background: density === d ? 'var(--token-accent-50)' : 'var(--token-surface-card)',
            color: density === d ? 'var(--token-accent-700)' : 'var(--token-text-secondary)',
            cursor: 'pointer',
            fontWeight: density === d ? 600 : 400,
            textTransform: 'capitalize',
          }}
        >
          {d}
        </button>
      ))}
    </div>
  )
}
