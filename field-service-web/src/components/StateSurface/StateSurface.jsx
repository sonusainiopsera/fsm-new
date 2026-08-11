/**
 * @fileoverview StateSurface — five named surface states with consistent wording and a11y.
 * empty | loading | degraded | permission-denied | error
 */

/** @typedef {'empty' | 'loading' | 'degraded' | 'permission-denied' | 'error'} SurfaceVariant */

const VALID_VARIANTS = /** @type {SurfaceVariant[]} */ ([
  'empty', 'loading', 'degraded', 'permission-denied', 'error'
])

const VARIANT_META = {
  empty: {
    icon: '○',
    heading: 'No items found',
    body: 'There is nothing to display here yet.',
    liveRegion: null,
  },
  loading: {
    icon: null,
    heading: 'Loading…',
    body: null,
    liveRegion: 'polite',
  },
  degraded: {
    icon: '⚠',
    heading: 'Showing stale data',
    body: 'Live data is temporarily unavailable. Displaying the last known values.',
    liveRegion: 'polite',
  },
  'permission-denied': {
    icon: '⊘',
    heading: 'Access denied',
    body: 'You do not have permission to view this content.',
    liveRegion: null,
  },
  error: {
    icon: '!',
    heading: 'Something went wrong',
    body: 'An error occurred. You can try again or contact support if the problem persists.',
    liveRegion: 'assertive',
  },
}

/**
 * @param {{
 *   variant: SurfaceVariant,
 *   onRetry?: () => void,
 *   isRefetching?: boolean,
 *   message?: string
 * }} props
 */
export function StateSurface({ variant, onRetry, isRefetching = false, message }) {
  if (!VALID_VARIANTS.includes(variant)) {
    if (typeof console !== 'undefined') {
      console.warn(`[StateSurface] Unknown variant "${variant}"`)
    }
  }

  const meta = VARIANT_META[variant] ?? VARIANT_META.error

  // Loading: skeleton on first load, quiet inline indicator on refetch
  if (variant === 'loading') {
    if (isRefetching) {
      return (
        <div
          role="status"
          aria-live="polite"
          aria-label="Updating…"
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 'var(--token-space-2)',
            padding: 'var(--token-space-2)',
            fontSize: 'var(--token-fs-13)',
            color: 'var(--token-text-secondary)',
          }}
        >
          <span aria-hidden="true" className="skeleton-spinner" style={{ display: 'inline-block', width: '1em', height: '1em', borderRadius: '50%', border: '2px solid var(--token-border-default)', borderTopColor: 'var(--token-accent-500)', animation: 'spin 1s linear infinite' }} />
          <span>Updating…</span>
        </div>
      )
    }

    return (
      <div
        role="status"
        aria-live="polite"
        aria-label="Loading content"
        style={{ padding: 'var(--token-space-6)' }}
      >
        {[...Array(3)].map((_, i) => (
          <div
            key={i}
            aria-hidden="true"
            style={{
              height: '1rem',
              borderRadius: 'var(--token-radius-control)',
              background: 'var(--token-neutral-200)',
              marginBottom: 'var(--token-space-3)',
              width: i === 2 ? '60%' : '100%',
              animation: 'skeleton-pulse 1.5s ease-in-out infinite',
            }}
          />
        ))}
        <span className="sr-only">Loading content, please wait.</span>
      </div>
    )
  }

  return (
    <div
      role={meta.liveRegion ? 'status' : undefined}
      aria-live={meta.liveRegion ?? undefined}
      data-variant={variant}
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 'var(--token-space-8)',
        gap: 'var(--token-space-4)',
        textAlign: 'center',
        color: 'var(--token-text-secondary)',
        fontFamily: 'var(--token-family-base)',
      }}
    >
      {meta.icon && (
        <span
          aria-hidden="true"
          style={{
            fontSize: 'var(--token-fs-30)',
            color: variant === 'error' ? 'var(--token-danger-default)'
                 : variant === 'permission-denied' ? 'var(--token-neutral-400)'
                 : variant === 'degraded' ? 'var(--token-warning-default)'
                 : 'var(--token-neutral-400)',
          }}
        >
          {meta.icon}
        </span>
      )}
      <div>
        <h2
          style={{
            fontSize: 'var(--token-fs-16)',
            fontWeight: 600,
            color: 'var(--token-text-primary)',
            margin: '0 0 var(--token-space-2)',
          }}
        >
          {meta.heading}
        </h2>
        <p style={{ fontSize: 'var(--token-fs-14)', margin: 0 }}>
          {message ?? meta.body}
        </p>
      </div>
      {onRetry && (variant === 'error' || variant === 'degraded') && (
        <button
          type="button"
          onClick={onRetry}
          style={{
            padding: 'var(--token-space-2) var(--token-space-4)',
            fontSize: 'var(--token-fs-14)',
            fontFamily: 'var(--token-family-base)',
            borderRadius: 'var(--token-radius-control)',
            border: '1px solid var(--token-border-default)',
            background: 'var(--token-surface-card)',
            color: 'var(--token-text-primary)',
            cursor: 'pointer',
          }}
        >
          Try again
        </button>
      )}
    </div>
  )
}

// Named re-exports for the five states (AC1)
/** @param {Omit<Parameters<typeof StateSurface>[0], 'variant'>} props */
export const EmptyState = (props) => <StateSurface {...props} variant="empty" />
/** @param {Omit<Parameters<typeof StateSurface>[0], 'variant'>} props */
export const LoadingState = (props) => <StateSurface {...props} variant="loading" />
/** @param {Omit<Parameters<typeof StateSurface>[0], 'variant'>} props */
export const DegradedState = (props) => <StateSurface {...props} variant="degraded" />
/** @param {Omit<Parameters<typeof StateSurface>[0], 'variant'>} props */
export const PermissionDeniedState = (props) => <StateSurface {...props} variant="permission-denied" />
/** @param {Omit<Parameters<typeof StateSurface>[0], 'variant'>} props */
export const ErrorState = (props) => <StateSurface {...props} variant="error" />
