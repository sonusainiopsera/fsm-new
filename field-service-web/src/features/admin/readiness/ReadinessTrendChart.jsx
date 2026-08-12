/**
 * @fileoverview Recharts line chart for the certification readiness weekly trend.
 *
 * Uses only design tokens for colours and sizing — no hardcoded visual values.
 * Conveys data by shape (line + dots) in addition to colour for contrast parity.
 *
 * @module features/admin/readiness/ReadinessTrendChart
 */

import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ReferenceLine,
  ResponsiveContainer,
} from 'recharts'

/**
 * @param {{ snapshots: Array<{isoWeek: string, readinessPercent: number|null, applicable: boolean}> }} props
 */
export function ReadinessTrendChart({ snapshots }) {
  // Reverse so oldest week is on the left
  const data = [...snapshots]
    .filter(s => s.applicable && s.readinessPercent != null)
    .reverse()
    .map(s => ({
      week:    s.isoWeek,
      percent: Number(s.readinessPercent),
    }))

  if (data.length === 0) return null

  return (
    <ResponsiveContainer width="100%" height={240}>
      <LineChart
        data={data}
        margin={{ top: 8, right: 16, bottom: 8, left: 0 }}
        aria-label="Certification readiness weekly trend"
        role="img"
      >
        <CartesianGrid strokeDasharray="3 3" stroke="var(--border-subtle)" />
        <XAxis
          dataKey="week"
          tick={{ fontSize: 12, fill: 'var(--text-secondary)' }}
          axisLine={{ stroke: 'var(--border-default)' }}
          tickLine={false}
        />
        <YAxis
          domain={[0, 100]}
          tickFormatter={v => `${v}%`}
          tick={{ fontSize: 12, fill: 'var(--text-secondary)' }}
          axisLine={false}
          tickLine={false}
        />
        <Tooltip
          formatter={v => [`${v}%`, 'Readiness']}
          contentStyle={{
            background:  'var(--surface-overlay)',
            border:      '1px solid var(--border-default)',
            borderRadius: 'var(--radius-sm)',
            fontSize:    'var(--text-sm)',
          }}
        />
        {/* 100% gate line */}
        <ReferenceLine
          y={100}
          stroke="var(--color-success-default)"
          strokeDasharray="6 3"
          label={{ value: 'Gate', position: 'right', fill: 'var(--color-success-default)', fontSize: 11 }}
        />
        <Line
          type="monotone"
          dataKey="percent"
          stroke="var(--color-accent-default)"
          strokeWidth={2}
          dot={{ r: 4, fill: 'var(--color-accent-default)', stroke: 'var(--surface-default)', strokeWidth: 2 }}
          activeDot={{ r: 6 }}
          name="Readiness"
        />
      </LineChart>
    </ResponsiveContainer>
  )
}
