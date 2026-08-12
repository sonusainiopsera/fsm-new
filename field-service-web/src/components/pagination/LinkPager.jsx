/**
 * @fileoverview LinkPager — link-driven pagination control.
 *
 * Navigation is driven exclusively by the server-returned next/prev URLs from
 * the response envelope's `links` object. The component never constructs
 * offset parameters independently (WO-175 contract: AC-1, AC-2).
 *
 * Prev/Next buttons have minimum 44×44 CSS pixel touch targets and are
 * keyboard-operable. Disabled state is explicit (aria-disabled + disabled
 * attribute) rather than purely visual.
 */

/**
 * @typedef {{ self?: string, next?: string | null, prev?: string | null }} PageLinks
 * @typedef {{ totalElements: number, totalPages: number, page: number, size: number, hasNext: boolean, hasPrev: boolean }} PageMeta
 */

/**
 * @param {{
 *   links: PageLinks,
 *   page: PageMeta,
 *   onNavigate: (url: string) => void,
 *   isFetching?: boolean
 * }} props
 */
export function LinkPager({ links, page, onNavigate, isFetching = false }) {
  if (!page || (!links.next && !links.prev)) return null

  const { totalElements, totalPages, page: currentPage } = page
  const hasNext = Boolean(links.next)
  const hasPrev = Boolean(links.prev)

  const buttonBase = {
    minWidth: '44px',
    minHeight: '44px',
    padding: '0 var(--token-space-4)',
    border: '1px solid var(--token-border-default)',
    borderRadius: 'var(--token-radius-control)',
    background: 'var(--token-surface-default)',
    fontSize: 'var(--token-fs-14)',
    fontFamily: 'var(--token-family-base)',
    cursor: 'pointer',
  }

  return (
    <nav
      aria-label="Service history pagination"
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
      <span aria-live="polite" aria-atomic="true">
        {totalElements > 0
          ? `${totalElements} ${totalElements === 1 ? 'request' : 'requests'}, page ${currentPage + 1}${totalPages > 0 ? ` of ${totalPages}` : ''}`
          : 'No requests found'}
      </span>

      <div style={{ display: 'flex', gap: 'var(--token-space-2)', alignItems: 'center' }}>
        {isFetching && (
          <span
            role="status"
            aria-label="Loading"
            style={{ fontSize: 'var(--token-fs-13)', color: 'var(--token-text-secondary)' }}
          >
            Loading…
          </span>
        )}

        <button
          type="button"
          disabled={!hasPrev || isFetching}
          aria-disabled={!hasPrev || isFetching}
          onClick={() => { if (hasPrev && links.prev) onNavigate(links.prev) }}
          aria-label="Previous page"
          data-testid="pager-prev"
          style={{
            ...buttonBase,
            color: !hasPrev ? 'var(--token-text-disabled)' : 'var(--token-text-primary)',
            cursor: !hasPrev ? 'not-allowed' : 'pointer',
          }}
        >
          ← Prev
        </button>

        <button
          type="button"
          disabled={!hasNext || isFetching}
          aria-disabled={!hasNext || isFetching}
          onClick={() => { if (hasNext && links.next) onNavigate(links.next) }}
          aria-label="Next page"
          data-testid="pager-next"
          style={{
            ...buttonBase,
            color: !hasNext ? 'var(--token-text-disabled)' : 'var(--token-text-primary)',
            cursor: !hasNext ? 'not-allowed' : 'pointer',
          }}
        >
          Next →
        </button>
      </div>
    </nav>
  )
}
