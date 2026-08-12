/**
 * @fileoverview WorkOrderRow — single row in the work order board table.
 *
 * AC-1: Uses shared Chip primitives for state, priority, and at-risk.
 * Risk and state conveyed by icon + text + colour (BR-34, not colour-only).
 * Long names truncate with a title attribute for screen readers.
 *
 * Keyboard: the row is focusable and activates the detail drawer on Enter/Space.
 */
import { Chip } from '../../../components/index.js'
import { SlaRiskChip } from '../../sla/SlaRiskChip.jsx'

/** Formats a deadline instant as a human-readable countdown or timestamp. */
function formatCountdown(isoString) {
  if (!isoString) return '—'
  const deadline = new Date(isoString)
  const now = new Date()
  const diffMs = deadline - now
  if (diffMs < 0) {
    return <span style={{ color: 'var(--token-danger-emphasis)' }} aria-label="Overdue">Overdue</span>
  }
  const totalMins = Math.floor(diffMs / 60_000)
  if (totalMins < 60) return `${totalMins}m`
  const hrs = Math.floor(totalMins / 60)
  const mins = totalMins % 60
  return `${hrs}h ${mins}m`
}

/**
 * @param {{
 *   row: import('../api/useWorkOrderSearch.js').WorkOrderBoardRow,
 *   isSelected: boolean,
 *   onSelect: (row: object) => void
 * }} props
 */
export function WorkOrderRow({ row, isSelected, onSelect }) {
  const tdStyle = {
    padding: 'var(--token-space-2) var(--token-space-3)',
    verticalAlign: 'middle',
    borderBottom: '1px solid var(--token-border-subtle)',
    fontSize: 'var(--token-fs-13)',
    color: 'var(--token-text-primary)',
  }

  const trStyle = {
    background: isSelected ? 'var(--token-accent-50)' : 'transparent',
    cursor: 'pointer',
    outline: 'none',
  }

  function handleKeyDown(e) {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      onSelect(row)
    }
  }

  const priorityValue = row.priority?.toLowerCase()
  const stateValue = row.state?.toLowerCase()

  return (
    <tr
      role="row"
      aria-selected={isSelected}
      tabIndex={0}
      style={trStyle}
      onClick={() => onSelect(row)}
      onKeyDown={handleKeyDown}
      data-testid={`work-order-row-${row.id}`}
    >
      {/* Reference */}
      <td style={tdStyle}>
        <span
          style={{ fontWeight: 500, fontFamily: 'var(--token-family-mono)', fontSize: 'var(--token-fs-12)' }}
        >
          {row.reference ?? row.id}
        </span>
      </td>

      {/* Customer / Site */}
      <td style={{ ...tdStyle, maxWidth: 200 }}>
        <div
          style={{
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
            fontWeight: 500,
          }}
          title={row.customerName ?? ''}
        >
          {row.customerName ?? '—'}
        </div>
        <div
          style={{
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
            fontSize: 'var(--token-fs-12)',
            color: 'var(--token-text-secondary)',
          }}
          title={row.siteName ?? ''}
        >
          {row.siteName ?? ''}
        </div>
      </td>

      {/* Priority */}
      <td style={tdStyle}>
        <Chip kind="priority" value={priorityValue} size="sm" />
      </td>

      {/* State */}
      <td style={tdStyle}>
        <Chip kind="state" value={stateValue} size="sm" />
      </td>

      {/* Assigned technician */}
      <td
        style={{ ...tdStyle, maxWidth: 160 }}
        title={row.assignedTechnicianName ?? 'Unassigned'}
      >
        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', display: 'block' }}>
          {row.assignedTechnicianName ?? <span style={{ color: 'var(--token-text-disabled)' }}>Unassigned</span>}
        </span>
      </td>

      {/* Response due */}
      <td style={{ ...tdStyle, fontVariantNumeric: 'var(--token-numeric)', textAlign: 'right' }}>
        <span aria-label={`Response due: ${row.responseDueAt ?? 'none'}`}>
          {formatCountdown(row.responseDueAt)}
        </span>
      </td>

      {/* Resolution due */}
      <td style={{ ...tdStyle, fontVariantNumeric: 'var(--token-numeric)', textAlign: 'right' }}>
        <span aria-label={`Resolution due: ${row.resolutionDueAt ?? 'none'}`}>
          {formatCountdown(row.resolutionDueAt)}
        </span>
      </td>

      {/* SLA risk chip — icon + text + colour, not colour-only (BR-34) */}
      <td style={{ ...tdStyle, textAlign: 'center' }}>
        {(row.riskLevel || row.atRisk) ? (
          <SlaRiskChip
            riskLevel={row.riskLevel ?? (row.atRisk ? 'at_risk' : 'healthy')}
            minutesRemaining={row.minutesRemaining ?? null}
            stale={row.slaStale ?? false}
            size="sm"
          />
        ) : null}
      </td>
    </tr>
  )
}
