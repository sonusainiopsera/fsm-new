import React from 'react';
import styles from './FactorBreakdownPanel.module.css';

const FACTOR_LABELS = {
  TRAVEL_EFFICIENCY:  'Travel efficiency',
  PARTS_AVAILABILITY: 'Parts availability',
  CERTIFICATION_MATCH: 'Certification match',
  WORKLOAD_BALANCE:   'Workload balance',
};

function labelFor(code) {
  return (
    FACTOR_LABELS[code] ??
    code.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase())
  );
}

/**
 * Renders all factor breakdowns for a single candidate.
 *
 * @param {{
 *   factors: Array<{
 *     factorCode: string,
 *     normalisedValue: number,
 *     weight: number,
 *     weightedContribution: number,
 *     explanation: string,
 *     degraded?: boolean,
 *     stale?: boolean,
 *   }>
 * }} props
 */
export function FactorBreakdownPanel({ factors }) {
  if (!factors?.length) return null;

  return (
    <ul className={styles.panel} aria-label="Factor breakdown">
      {factors.map(factor => {
        const label = labelFor(factor.factorCode);
        const pct   = Math.round((factor.normalisedValue ?? 0) * 100);

        return (
          <li key={factor.factorCode} className={styles.factor}>
            <div className={styles.header}>
              <span className={styles.label}>
                {label}
                {(factor.degraded || factor.stale) && (
                  <span
                    className={styles.degradedMark}
                    aria-label={`${label} data degraded`}
                    title="Data quality degraded or stale"
                  >
                    *
                  </span>
                )}
              </span>
              <span className={styles.weight} aria-label={`weight ${Math.round(factor.weight * 100)}%`}>
                {Math.round(factor.weight * 100)}% weight
              </span>
            </div>

            <div
              className={styles.barTrack}
              role="meter"
              aria-label={`${label}: ${pct}%`}
              aria-valuenow={pct}
              aria-valuemin={0}
              aria-valuemax={100}
            >
              <div
                className={styles.barFill}
                style={{ width: `${pct}%` }}
                aria-hidden="true"
              />
            </div>
            <p className={styles.pct} aria-hidden="true">{pct}%</p>

            <p className={styles.explanation}>{factor.explanation}</p>
          </li>
        );
      })}
    </ul>
  );
}
