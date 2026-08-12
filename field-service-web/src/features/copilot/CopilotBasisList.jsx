/**
 * @fileoverview CopilotBasisList — accessible disclosure listing the asset and
 * contributing prior work orders that informed the copilot answer (WO-179 AC-4).
 *
 * Rules:
 * - Rendered only when a non-empty basis is provided.
 * - aria-expanded on the disclosure toggle.
 * - Each prior work order links to its record (technician-scoped URL) where the
 *   technician has access; links never auto-activate from model content.
 * - The list is inside the sheet scroll region, not above rendered answer text.
 */
import { useState } from 'react'
import styles from './CopilotSheet.module.css'

/**
 * @param {{
 *   basis: import('./useCopilotStream.js').CopilotBasis | null
 * }} props
 */
export function CopilotBasisList({ basis }) {
  const [expanded, setExpanded] = useState(false)

  if (!basis) return null

  const hasAsset = !!(basis.assetId || basis.assetTag)
  const priorWos = Array.isArray(basis.priorWorkOrders) ? basis.priorWorkOrders : []

  if (!hasAsset && priorWos.length === 0) return null

  return (
    <div className={styles.basisSection}>
      <button
        type="button"
        className={styles.basisToggle}
        aria-expanded={expanded}
        aria-controls="copilot-basis-list"
        onClick={() => setExpanded(v => !v)}
        data-testid="basis-toggle"
      >
        <span className={styles.basisToggleIcon} aria-hidden="true">
          {expanded ? '▾' : '▸'}
        </span>
        <span>Basis ({priorWos.length} prior work order{priorWos.length !== 1 ? 's' : ''}
          {hasAsset ? ', asset context' : ''})
        </span>
      </button>

      <div
        id="copilot-basis-list"
        className={styles.basisContent}
        hidden={!expanded}
        data-testid="basis-content"
      >
        {hasAsset && (
          <div className={styles.basisAsset}>
            <span className={styles.basisLabel}>Asset</span>
            <span>{basis.assetTag ?? basis.assetId}</span>
          </div>
        )}

        {priorWos.length > 0 && (
          <ul className={styles.basisList} aria-label="Contributing prior work orders">
            {priorWos.map((wo) => (
              <li key={wo.workOrderId} className={styles.basisItem}>
                {/* Link to technician-scoped job detail — WO reference is safe to display */}
                <a
                  href={`/technician/jobs/${encodeURIComponent(wo.workOrderId)}`}
                  className={styles.basisLink}
                  data-testid={`basis-wo-link-${wo.workOrderId}`}
                >
                  {wo.reference ?? wo.workOrderId}
                </a>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}
