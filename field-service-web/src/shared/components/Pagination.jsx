/**
 * @fileoverview Server-side pagination controls bound to API page metadata.
 *
 * Renders prev/next buttons with current page indicator. All navigation is
 * keyboard operable with minimum 44px touch targets. Disabled states are
 * explicit (not just opacity) so screen readers announce them correctly.
 *
 * @module shared/components/Pagination
 */

/**
 * @param {{
 *   page: import('../../api/pagination.js').PageMeta,
 *   onPageChange: (page: number) => void
 * }} props
 */
export function Pagination({ page, onPageChange }) {
  if (!page || page.totalPages <= 1) return null

  const { page: currentPage, totalPages, totalElements } = page

  return (
    <nav
      aria-label="Pagination"
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 'var(--token-space-3)',
        padding: 'var(--token-space-3) 0',
        fontFamily: 'var(--token-family-base)',
        fontSize: 'var(--token-fs-14)',
        color: 'var(--token-text-secondary)',
      }}
    >
      <span>
        {totalElements} {totalElements === 1 ? 'item' : 'items'}, page {currentPage + 1} of {totalPages}
      </span>
      <div style={{ display: 'flex', gap: 'var(--token-space-2)' }}>
        <button
          type="button"
          disabled={currentPage === 0}
          onClick={() => onPageChange(currentPage - 1)}
          aria-label="Previous page"
          style={{
            minWidth: '44px',
            minHeight: '44px',
            padding: '0 var(--token-space-3)',
            border: '1px solid var(--token-border-default)',
            borderRadius: 'var(--token-radius-control)',
            background: 'var(--token-surface-default)',
            color: currentPage === 0 ? 'var(--token-text-disabled)' : 'var(--token-text-primary)',
            cursor: currentPage === 0 ? 'not-allowed' : 'pointer',
            fontSize: 'var(--token-fs-14)',
            fontFamily: 'var(--token-family-base)',
          }}
        >
          ← Prev
        </button>
        <button
          type="button"
          disabled={currentPage >= totalPages - 1}
          onClick={() => onPageChange(currentPage + 1)}
          aria-label="Next page"
          style={{
            minWidth: '44px',
            minHeight: '44px',
            padding: '0 var(--token-space-3)',
            border: '1px solid var(--token-border-default)',
            borderRadius: 'var(--token-radius-control)',
            background: 'var(--token-surface-default)',
            color: currentPage >= totalPages - 1 ? 'var(--token-text-disabled)' : 'var(--token-text-primary)',
            cursor: currentPage >= totalPages - 1 ? 'not-allowed' : 'pointer',
            fontSize: 'var(--token-fs-14)',
            fontFamily: 'var(--token-family-base)',
          }}
        >
          Next →
        </button>
      </div>
    </nav>
  )
}
