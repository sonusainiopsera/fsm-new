import React from 'react';
import styles from './AccessibleSeriesTable.module.css';

/**
 * Accessible tabular representation of chart data.
 *
 * Visually collapsed by default; keyboard accessible via the "Show data table"
 * button. Linked to MetricChart via aria-describedby on the chart's SVG element.
 *
 * @param {{
 *   id: string,
 *   caption: string,
 *   series: Array<{ key: string, name: string, accent?: boolean }>,
 *   data: Array<Record<string, string | number>>,
 * }} props
 */
export function AccessibleSeriesTable({ id, caption, series, data }) {
  const [expanded, setExpanded] = React.useState(false);

  if (!series || series.length === 0 || !data || data.length === 0) {
    return null;
  }

  return (
    <div id={id} className={styles.container}>
      <button
        className={styles.toggle}
        type="button"
        aria-expanded={expanded}
        onClick={() => setExpanded((v) => !v)}
      >
        {expanded ? 'Hide data table' : 'Show data table'}
      </button>

      {expanded && (
        <div className={styles.tableWrap} role="region" aria-label={`Data table: ${caption}`} tabIndex={0}>
          <table className={styles.table}>
            <caption className={styles.caption}>{caption}</caption>
            <thead>
              <tr>
                <th scope="col">Period</th>
                {series.map((s) => (
                  <th key={s.key} scope="col">
                    {s.name}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {data.map((row, i) => (
                <tr key={i}>
                  <td>{row.period ?? row.date ?? row.label ?? i + 1}</td>
                  {series.map((s) => (
                    <td key={s.key} className="numeric">
                      {row[s.key] != null ? row[s.key] : '—'}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
