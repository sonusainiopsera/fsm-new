/**
 * CertificationChip — renders API-derived certification currency status.
 *
 * States: current, expiring-soon (≤30 days), expired.
 * Each state uses icon + text so colour is never the sole signal (WCAG 1.4.1).
 *
 * IMPORTANT: This component MUST only render what the API returns.
 * It MUST NOT perform any date math. `current` and `daysUntilExpiry` come
 * directly from the server-side query-time evaluation.
 *
 * @module features/admin/CertificationChip
 */

import React from 'react';
import styles from './admin.module.css';

const EXPIRING_SOON_THRESHOLD = 30;

/**
 * @param {{
 *   current: boolean,
 *   daysUntilExpiry: number | null,
 *   expiresOn: string | null,
 * }} props
 */
export function CertificationChip({ current, daysUntilExpiry, expiresOn }) {
  // Derive display state from server-provided values only — no Date math
  if (!current) {
    return (
      <span
        className={[styles.certChip, styles.certChipExpired].join(' ')}
        aria-label="Certification expired"
      >
        <span aria-hidden="true">✕</span> Expired
        {expiresOn && <span className="visually-hidden"> on {expiresOn}</span>}
      </span>
    );
  }

  const isExpiringSoon = daysUntilExpiry !== null && daysUntilExpiry <= EXPIRING_SOON_THRESHOLD;

  if (isExpiringSoon) {
    return (
      <span
        className={[styles.certChip, styles.certChipExpiringSoon].join(' ')}
        aria-label={`Certification expiring in ${daysUntilExpiry} days`}
      >
        <span aria-hidden="true">⚠</span> Expiring in {daysUntilExpiry}d
      </span>
    );
  }

  return (
    <span
      className={[styles.certChip, styles.certChipCurrent].join(' ')}
      aria-label={daysUntilExpiry === null ? 'Certification current (no expiry)' : `Certification current — expires in ${daysUntilExpiry} days`}
    >
      <span aria-hidden="true">✓</span> Current
      {daysUntilExpiry !== null && ` · ${daysUntilExpiry}d`}
    </span>
  );
}
