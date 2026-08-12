import React, { useState } from 'react';
import { FactorBreakdownPanel } from './FactorBreakdownPanel.jsx';
import styles from './RecommendationCard.module.css';

/**
 * Displays a single ranked candidate row with an expandable factor breakdown.
 *
 * @param {{
 *   candidate: {
 *     technicianId: string,
 *     technicianName: string,
 *     rank: number,
 *     score: number,
 *     travelEstimateDegraded: boolean,
 *     factors: unknown[],
 *   },
 *   defaultExpanded?: boolean,
 * }} props
 */
export function RecommendationCard({ candidate, defaultExpanded = false }) {
  const [expanded, setExpanded] = useState(defaultExpanded);

  const { technicianId, technicianName, rank, score, travelEstimateDegraded, factors } = candidate;
  const panelId = `factor-panel-${technicianId}`;
  const scorePct = ((score ?? 0) * 100).toFixed(1);
  const hasFactors = Array.isArray(factors) && factors.length > 0;

  return (
    <li className={styles.card}>
      <div className={styles.summary}>
        <span className={styles.rank} aria-label={`rank ${rank}`}>
          #{rank}
        </span>

        <span className={styles.name} title={technicianName}>
          {technicianName}
        </span>

        {travelEstimateDegraded && (
          <span
            className={styles.degradedIndicator}
            aria-label="travel estimate only"
            title="Travel time is an estimate — live data unavailable"
          >
            ~est
          </span>
        )}

        <span className={styles.score} aria-label={`composite score ${scorePct}%`}>
          {scorePct}%
        </span>

        {hasFactors && (
          <button
            className={styles.expandBtn}
            aria-expanded={expanded}
            aria-controls={panelId}
            onClick={() => setExpanded(e => !e)}
          >
            {expanded ? 'Hide factors' : 'Show factors'}
          </button>
        )}
      </div>

      {hasFactors && (
        <div
          id={panelId}
          className={styles.breakdown}
          hidden={!expanded}
          aria-hidden={!expanded}
        >
          <FactorBreakdownPanel factors={factors} />
        </div>
      )}
    </li>
  );
}
