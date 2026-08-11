import React from 'react';
import styles from './ChartTableEquivalent.module.css';

/**
 * @typedef {Object} ChartSeries
 * @property {string} key   - Matches the key in each data row
 * @property {string} name  - Human-readable series name for the column header
 */

/**
 * @typedef {Object} ChartDataRow
 * @property {string} label  - Period / category label for the row
 * @property {Object.<string, number>} [key] - Series values keyed by series.key
 */

/**
 * Accessible tabular equivalent for a chart.
 *
 * Renders the same series data as a `<table>` so screen-reader users and
 * keyboard-only users can access the underlying values.  Pair this component
 * with the chart it describes via `aria-details` on the chart container and
 * `id` on this table.
 *
 * Empty series: renders a valid but empty table with a visually-present
 * caption, satisfying screen readers without an unlabelled blank canvas.
 *
 * @param {{
 *   id?: string,
 *   caption: string,
 *   series: ChartSeries[],
 *   data: ChartDataRow[],
 * }} props
 */
export function ChartTableEquivalent({ id, caption, series, data }) {
  return (
    <div className={styles.wrapper}>
      <table id={id} className={styles.table}>
        <caption className={styles.caption}>{caption}</caption>
        <thead>
          <tr>
            <th scope="col" className={styles.th}>Period</th>
            {series.map((s) => (
              <th key={s.key} scope="col" className={`${styles.th} ${styles.numeric}`}>
                {s.name}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {data.length === 0 ? (
            <tr>
              <td
                colSpan={series.length + 1}
                className={styles.empty}
                aria-label="No data available"
              >
                No data
              </td>
            </tr>
          ) : (
            data.map((row) => (
              <tr key={row.label} className={styles.row}>
                <td className={styles.td}>{row.label}</td>
                {series.map((s) => (
                  <td key={s.key} className={`${styles.td} ${styles.numeric}`}>
                    {row[s.key] ?? '—'}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}
