/**
 * LinkPager — pagination control that navigates only via server-provided
 * next/prev links from the response envelope.
 *
 * Contract:
 *  - NEVER constructs its own offsets or page numbers.
 *  - Accepts `links.next` and `links.prev` from the server envelope.
 *  - Calls `onNext` / `onPrev` when the user presses the buttons;
 *    the parent is responsible for following the actual URL.
 *  - Disables the button if the link is absent or null.
 *  - Keyboard-accessible: both buttons are <button type="button">.
 *
 * @param {{
 *   page: { number: number, size: number, totalElements: number, totalPages: number } | null,
 *   links: { next: string | null, prev: string | null },
 *   onNext: () => void,
 *   onPrev: () => void,
 *   isLoading?: boolean,
 *   className?: string,
 * }} props
 */

import React from 'react';
import styles from './LinkPager.module.css';

export function LinkPager({ page, links, onNext, onPrev, isLoading = false, className = '' }) {
  const hasPrev = Boolean(links?.prev);
  const hasNext = Boolean(links?.next);

  const currentPage = (page?.number ?? 0) + 1; // convert 0-based to 1-based for display
  const totalPages  = page?.totalPages ?? 1;

  return (
    <nav
      className={[styles.pager, className].filter(Boolean).join(' ')}
      aria-label="Pagination"
    >
      <button
        type="button"
        className={styles.btn}
        onClick={onPrev}
        disabled={!hasPrev || isLoading}
        aria-label="Previous page"
      >
        ← Previous
      </button>

      <span className={styles.info} aria-live="polite" aria-atomic="true">
        {page
          ? `Page ${currentPage} of ${totalPages}`
          : isLoading ? 'Loading…' : ''}
      </span>

      <button
        type="button"
        className={styles.btn}
        onClick={onNext}
        disabled={!hasNext || isLoading}
        aria-label="Next page"
      >
        Next →
      </button>
    </nav>
  );
}
