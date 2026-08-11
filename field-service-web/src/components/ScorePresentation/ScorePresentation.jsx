import React from 'react';

import styles from './ScorePresentation.module.css';

/**
 * @typedef {{
 *   label: string,
 *   weight: number,
 *   normalizedValue: number,
 * }} ScoreFactor
 */

/**
 * Austere score presentation — monochrome numeral, neutral 4 px track,
 * and per-factor micro-bars with text labels.
 * No semantic colour, no medals, no celebratory treatment (BR-33).
 *
 * @param {{
 *   score: number,
 *   maxScore?: number,
 *   label?: string,
 *   factors?: ScoreFactor[],
 * }} props
 */
export function ScorePresentation({ score, maxScore = 100, label = 'Score', factors = [] }) {
  const pct = Math.min(100, Math.max(0, (score / maxScore) * 100));

  return (
    <div className={styles.container} role="region" aria-label={label}>
      <div className={styles.scoreRow}>
        <span className={`${styles.numeral} numeric`} aria-label={`${label}: ${score} out of ${maxScore}`}>
          {score}
        </span>
        <div
          className={styles.track}
          role="progressbar"
          aria-valuenow={score}
          aria-valuemin={0}
          aria-valuemax={maxScore}
          aria-label={`${pct.toFixed(0)}%`}
        >
          <div className={styles.fill} style={{ width: `${pct}%` }} />
        </div>
      </div>

      {factors.length > 0 && (
        <div className={styles.factors}>
          {factors.map((f, i) => {
            const factorPct = Math.min(100, Math.max(0, f.normalizedValue * 100));
            return (
              <div key={i} className={styles.factorRow}>
                <span className={styles.factorLabel} title={f.label}>{f.label}</span>
                <div
                  className={styles.microTrack}
                  role="progressbar"
                  aria-valuenow={Math.round(factorPct)}
                  aria-valuemin={0}
                  aria-valuemax={100}
                  aria-label={`${f.label}: ${Math.round(factorPct)}%`}
                >
                  <div className={styles.microFill} style={{ width: `${factorPct}%` }} />
                </div>
                <span className={`${styles.factorValue} numeric`}>
                  {Math.round(factorPct)}%
                </span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
