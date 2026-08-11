/**
 * @fileoverview ChartTableEquivalent — accessible tabular representation of chart data.
 *
 * Every chart must expose this table so screen readers and keyboard users can
 * access the same values as sighted users. The table is always rendered and
 * keyboard-reachable; visual charts can optionally toggle it via aria-expanded.
 *
 * Usage:
 *   <ChartTableEquivalent
 *     data={[{ name: 'Jan', revenue: 4000, cost: 2400 }]}
 *     series={[{ key: 'revenue', label: 'Revenue' }, { key: 'cost', label: 'Cost' }]}
 *     caption="Monthly Revenue vs Cost"
 *   />
 */

import { EmptyState } from '../components/StateSurface/StateSurface.jsx'

/**
 * @param {{
 *   data: Array<Record<string, string | number>>,
 *   series: Array<{ key: string, label: string }>,
 *   caption?: string,
 *   nameKey?: string,
 *   nameLabel?: string,
 *   className?: string,
 * }} props
 */
export function ChartTableEquivalent({
  data = [],
  series = [],
  caption = 'Chart data',
  nameKey = 'name',
  nameLabel = 'Name',
  className,
}) {
  if (!data.length || !series.length) {
    return <EmptyState message="No chart data available" />
  }

  return (
    <div className={className} role="region" aria-label={caption}>
      <table
        aria-label={caption}
        style={{
          width: '100%',
          borderCollapse: 'collapse',
          fontFamily: 'var(--token-family-base)',
          fontSize: 'var(--token-fs-14)',
          color: 'var(--token-text-primary)',
        }}
      >
        <caption
          style={{
            textAlign: 'left',
            padding: 'var(--token-space-2) 0',
            fontWeight: 600,
            color: 'var(--token-text-primary)',
          }}
        >
          {caption}
        </caption>
        <thead>
          <tr>
            <th
              scope="col"
              style={{
                padding: 'var(--token-space-2) var(--token-space-3)',
                borderBottom: '2px solid var(--token-border-default)',
                textAlign: 'left',
                fontWeight: 600,
              }}
            >
              {nameLabel}
            </th>
            {series.map(({ key, label }) => (
              <th
                key={key}
                scope="col"
                style={{
                  padding: 'var(--token-space-2) var(--token-space-3)',
                  borderBottom: '2px solid var(--token-border-default)',
                  textAlign: 'right',
                  fontWeight: 600,
                }}
              >
                {label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {data.map((row, rowIndex) => (
            <tr
              key={row[nameKey] ?? rowIndex}
              style={{
                borderBottom: '1px solid var(--token-border-default)',
              }}
            >
              <th
                scope="row"
                style={{
                  padding: 'var(--token-space-2) var(--token-space-3)',
                  textAlign: 'left',
                  fontWeight: 400,
                }}
              >
                {String(row[nameKey] ?? '')}
              </th>
              {series.map(({ key }) => (
                <td
                  key={key}
                  data-series={key}
                  style={{
                    padding: 'var(--token-space-2) var(--token-space-3)',
                    textAlign: 'right',
                    fontVariantNumeric: 'var(--token-numeric)',
                  }}
                >
                  {row[key] !== undefined && row[key] !== null ? String(row[key]) : '—'}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
