/**
 * @fileoverview WorkOrderTable — board projection table.
 *
 * AC-1: Proper table semantics, column headers as sort controls,
 * all chips from shared library, no screen-local styling.
 * AC-3/AC-8: Accessible keyboard navigation per row.
 */
import { WorkOrderRow } from './WorkOrderRow.jsx'

const COLUMNS = [
  { key: 'reference', label: 'Ref', sortField: 'reference', align: 'left' },
  { key: 'customer', label: 'Customer / Site', sortField: null, align: 'left' },
  { key: 'priority', label: 'Priority', sortField: 'priority', align: 'left' },
  { key: 'state', label: 'State', sortField: 'state', align: 'left' },
  { key: 'technician', label: 'Technician', sortField: 'assignedTechnicianName', align: 'left' },
  { key: 'responseDue', label: 'Response due', sortField: 'responseDueAt', align: 'right' },
  { key: 'resolutionDue', label: 'Resolution due', sortField: 'resolutionDueAt', align: 'right' },
  { key: 'atRisk', label: 'Risk', sortField: 'atRisk', align: 'center' },
]

/**
 * @param {{
 *   rows: import('../api/useWorkOrderSearch.js').WorkOrderBoardRow[],
 *   selectedId: string | null,
 *   sort: string | undefined,
 *   onSort: (field: string) => void,
 *   onRowSelect: (row: object) => void
 * }} props
 */
export function WorkOrderTable({ rows, selectedId, sort, onSort, onRowSelect }) {
  const sortField = sort?.split(':')[0]
  const sortDir = sort?.split(':')[1] ?? 'ASC'

  function getSortIcon(field) {
    if (sortField !== field) return '⇅'
    return sortDir === 'ASC' ? '↑' : '↓'
  }

  function handleSortKeyDown(e, field) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      onSort(field)
    }
  }

  const thBase = {
    padding: 'var(--token-space-2) var(--token-space-3)',
    fontWeight: 600,
    fontSize: 'var(--token-fs-12)',
    color: 'var(--token-text-secondary)',
    textTransform: 'uppercase',
    letterSpacing: '0.04em',
    borderBottom: '2px solid var(--token-border-default)',
    whiteSpace: 'nowrap',
    position: 'sticky',
    top: 0,
    background: 'var(--token-surface-card)',
    zIndex: 1,
  }

  return (
    <div
      style={{ overflowX: 'auto', overflowY: 'visible' }}
      role="region"
      aria-label="Work orders"
    >
      <table
        style={{ width: '100%', borderCollapse: 'collapse', tableLayout: 'fixed' }}
        aria-label="Work order board"
        role="grid"
      >
        <colgroup>
          <col style={{ width: '8%' }} />
          <col style={{ width: '22%' }} />
          <col style={{ width: '10%' }} />
          <col style={{ width: '12%' }} />
          <col style={{ width: '14%' }} />
          <col style={{ width: '10%' }} />
          <col style={{ width: '10%' }} />
          <col style={{ width: '8%' }} />
        </colgroup>
        <thead>
          <tr role="row">
            {COLUMNS.map(col => {
              const isSortActive = col.sortField && sortField === col.sortField
              return (
                <th
                  key={col.key}
                  role="columnheader"
                  scope="col"
                  aria-sort={
                    col.sortField
                      ? isSortActive
                        ? sortDir === 'ASC' ? 'ascending' : 'descending'
                        : 'none'
                      : undefined
                  }
                  style={{
                    ...thBase,
                    textAlign: col.align,
                    cursor: col.sortField ? 'pointer' : undefined,
                    color: isSortActive ? 'var(--token-text-primary)' : 'var(--token-text-secondary)',
                  }}
                  tabIndex={col.sortField ? 0 : undefined}
                  onClick={col.sortField ? () => onSort(col.sortField) : undefined}
                  onKeyDown={col.sortField ? (e) => handleSortKeyDown(e, col.sortField) : undefined}
                  title={col.sortField ? `Sort by ${col.label}` : undefined}
                >
                  {col.label}
                  {col.sortField && (
                    <span aria-hidden="true" style={{ marginLeft: 'var(--token-space-1)', opacity: 0.6 }}>
                      {getSortIcon(col.sortField)}
                    </span>
                  )}
                </th>
              )
            })}
          </tr>
        </thead>
        <tbody>
          {rows.map(row => (
            <WorkOrderRow
              key={row.id}
              row={row}
              isSelected={row.id === selectedId}
              onSelect={onRowSelect}
            />
          ))}
        </tbody>
      </table>
    </div>
  )
}
