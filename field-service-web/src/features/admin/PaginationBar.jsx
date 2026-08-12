/**
 * PaginationBar — server-side pagination controls.
 *
 * Renders "Page X of Y (N total)" and Prev/Next buttons.
 * Handles edge case where current page exceeds totalPages (recovers to last page).
 *
 * @module features/admin/PaginationBar
 */

import React from 'react';
import styles from './admin.module.css';

/**
 * @param {{
 *   page: number,
 *   totalPages: number,
 *   totalElements: number,
 *   onPageChange: (page: number) => void,
 * }} props
 */
export function PaginationBar({ page, totalPages, totalElements, onPageChange }) {
  const safeTotal = Math.max(1, totalPages);
  const safePage = Math.min(page, safeTotal - 1);

  // Auto-recover: if current page is beyond bounds (rows deleted by another user)
  if (page > 0 && page >= safeTotal && totalPages > 0) {
    onPageChange(safeTotal - 1);
  }

  const hasPrev = safePage > 0;
  const hasNext = safePage < safeTotal - 1;

  return (
    <div className={styles.pagination} aria-label="Pagination">
      <span>
        {totalElements} {totalElements === 1 ? 'record' : 'records'} — page {safePage + 1} of {safeTotal}
      </span>
      <div className={styles.paginationControls}>
        <button
          className={styles.paginationBtn}
          onClick={() => onPageChange(safePage - 1)}
          disabled={!hasPrev}
          aria-label="Previous page"
        >
          ‹ Prev
        </button>
        <button
          className={styles.paginationBtn}
          onClick={() => onPageChange(safePage + 1)}
          disabled={!hasNext}
          aria-label="Next page"
        >
          Next ›
        </button>
      </div>
    </div>
  );
}
