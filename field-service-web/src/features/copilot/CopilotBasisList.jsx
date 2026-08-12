/**
 * CopilotBasisList — accessible disclosure revealing the grounding evidence behind a copilot answer.
 *
 * Renders the asset name and each contributing prior work order from the basis payload.
 * Each prior work order links to its record where the technician has access.
 * aria-expanded reflects open/closed state per WO-179 accessibility requirements.
 *
 * @module features/copilot/CopilotBasisList
 */

import React, { useState } from 'react';
import styles from './CopilotSheet.module.css';

/**
 * @typedef {{
 *   assetId: string | null,
 *   assetName: string | null,
 *   priorWorkOrders: Array<{ id: string, reference: string, summary: string }>,
 * }} CopilotBasis
 */

/**
 * @param {{
 *   basis: CopilotBasis,
 *   workOrderBase?: string,
 * }} props
 */
export function CopilotBasisList({ basis, workOrderBase = '/technician/jobs' }) {
  const [open, setOpen] = useState(false);

  const hasBasis = !!basis.assetName || (basis.priorWorkOrders?.length > 0);
  if (!hasBasis) return null;

  return (
    <div className={styles.basisSection}>
      <button
        type="button"
        className={styles.basisToggle}
        aria-expanded={open}
        aria-controls="copilot-basis-content"
        onClick={() => setOpen((prev) => !prev)}
      >
        <span className={styles.basisToggleIcon} aria-hidden="true">
          {open ? '▾' : '▸'}
        </span>
        Basis
        {basis.priorWorkOrders?.length > 0 && (
          <span className={styles.basisCount} aria-label={`${basis.priorWorkOrders.length} prior work orders`}>
            {' '}({basis.priorWorkOrders.length})
          </span>
        )}
      </button>

      <div
        id="copilot-basis-content"
        className={styles.basisContent}
        hidden={!open}
      >
        {basis.assetName && (
          <div className={styles.basisAsset}>
            <span className={styles.basisLabel}>Asset</span>
            <span className={styles.basisValue}>{basis.assetName}</span>
          </div>
        )}

        {basis.priorWorkOrders?.length > 0 && (
          <div className={styles.basisPriorSection}>
            <p className={styles.basisLabel}>Prior work orders</p>
            <ul className={styles.basisList} role="list">
              {basis.priorWorkOrders.map((wo) => (
                <li key={wo.id} className={styles.basisItem}>
                  <a
                    href={`${workOrderBase}/${wo.id}`}
                    className={styles.basisLink}
                    aria-label={`Work order ${wo.reference}: ${wo.summary}`}
                  >
                    <span className={styles.basisRef}>{wo.reference}</span>
                    <span className={styles.basisSummary}>{wo.summary}</span>
                  </a>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </div>
  );
}
