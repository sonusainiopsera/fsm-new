/**
 * AssetHistoryList — prior service history for the asset on this job.
 *
 * Fetches GET /api/v1/assets/{assetId}/service-history?limit=5.
 * Renders an explicit empty state when no history exists, never a spinner.
 *
 * @module app/technician/components/AssetHistoryList
 */

import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '../../../api/http.js';
import styles from './AssetHistoryList.module.css';

function formatDate(iso) {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

/**
 * @param {{ assetId: string | null | undefined }} props
 */
export function AssetHistoryList({ assetId }) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['assets', assetId, 'service-history'],
    queryFn: () => apiFetch(`/assets/${assetId}/service-history?limit=5`),
    enabled: !!assetId,
    staleTime: 120_000,
  });

  if (!assetId) return null;

  if (isLoading) {
    return (
      <section className={styles.section} aria-label="Asset service history">
        <h2 className={styles.heading}>Prior service</h2>
        <p className={styles.empty} aria-live="polite">Loading…</p>
      </section>
    );
  }

  if (isError) {
    return (
      <section className={styles.section} aria-label="Asset service history">
        <h2 className={styles.heading}>Prior service</h2>
        <p className={styles.empty}>Unable to load service history.</p>
      </section>
    );
  }

  const records = Array.isArray(data) ? data : [];

  return (
    <section className={styles.section} aria-label="Asset service history">
      <h2 className={styles.heading}>Prior service</h2>
      {records.length === 0 ? (
        <p className={styles.empty}>No prior service records for this asset.</p>
      ) : (
        <ul className={styles.list}>
          {records.map((r) => (
            <li key={r.id} className={styles.item}>
              <span className={styles.date}>{formatDate(r.resolvedAt)}</span>
              <span className={styles.ref}>{r.reference}</span>
              {r.faultSummary && (
                <p className={styles.fault}>{r.faultSummary}</p>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
