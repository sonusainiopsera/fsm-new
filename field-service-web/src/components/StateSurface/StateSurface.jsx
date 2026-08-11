import React from 'react';

import { Button } from '../Button/Button.jsx';

import styles from './StateSurface.module.css';

/**
 * @typedef {'empty' | 'loading' | 'degraded' | 'permission-denied' | 'error'} StateSurfaceVariant
 */

const VALID_VARIANTS = ['empty', 'loading', 'degraded', 'permission-denied', 'error'];

const DEFAULT_META = {
  empty: {
    icon: '○',
    title: 'Nothing here yet',
    description: 'There are no items to display.',
    liveRegion: 'polite',
  },
  loading: {
    icon: null,
    title: 'Loading',
    description: 'Please wait…',
    liveRegion: 'polite',
  },
  degraded: {
    icon: '⚠',
    title: 'Showing stale data',
    description: 'Live data is temporarily unavailable. Displaying the last known values.',
    liveRegion: 'polite',
  },
  'permission-denied': {
    icon: '⊘',
    title: 'Access restricted',
    description: 'You do not have permission to view this content.',
    liveRegion: 'assertive',
  },
  error: {
    icon: '✕',
    title: 'Something went wrong',
    description: 'An unexpected error occurred. Please try again.',
    liveRegion: 'assertive',
  },
};

/**
 * Unified named-state surface. All five variants share consistent wording,
 * iconography, retry affordance and aria semantics.
 * Uses skeletons on first load, quiet inline indicator on refetch.
 *
 * @param {{
 *   variant: StateSurfaceVariant,
 *   isRefetch?: boolean,
 *   title?: string,
 *   description?: string,
 *   onRetry?: () => void,
 *   retryLabel?: string,
 * }} props
 */
export function StateSurface({
  variant,
  isRefetch = false,
  title,
  description,
  onRetry,
  retryLabel = 'Try again',
}) {
  if (!VALID_VARIANTS.includes(variant)) {
    console.warn(`StateSurface: unknown variant "${variant}"`);
  }

  const meta = DEFAULT_META[variant] ?? DEFAULT_META.error;
  const displayTitle = title ?? meta.title;
  const displayDescription = description ?? meta.description;

  if (variant === 'loading' && isRefetch) {
    return (
      <div className={styles.refetchBar} role="status" aria-live="polite" aria-label="Updating…">
        <span className={styles.refetchDot} aria-hidden="true" />
        <span>Updating…</span>
      </div>
    );
  }

  if (variant === 'loading' && !isRefetch) {
    return (
      <div className={styles.surface} role="status" aria-live="polite" aria-label="Loading">
        <div className={styles.skeletonRow} aria-hidden="true">
          <div className={styles.skeletonLine} />
          <div className={styles.skeletonLine} />
          <div className={styles.skeletonLine} />
        </div>
        <span className="sr-only">Loading, please wait</span>
      </div>
    );
  }

  return (
    <div
      className={styles.surface}
      role={meta.liveRegion === 'assertive' ? 'alert' : 'status'}
      aria-live={meta.liveRegion}
    >
      {meta.icon && <span className={styles.icon} aria-hidden="true">{meta.icon}</span>}
      <p className={styles.title}>{displayTitle}</p>
      {displayDescription && <p className={styles.description}>{displayDescription}</p>}
      {onRetry && variant !== 'permission-denied' && (
        <div className={styles.retryBtn}>
          <Button variant="secondary" onClick={onRetry}>{retryLabel}</Button>
        </div>
      )}
    </div>
  );
}

export const EmptyState = (props) => <StateSurface {...props} variant="empty" />;
export const LoadingState = (props) => <StateSurface {...props} variant="loading" />;
export const DegradedState = (props) => <StateSurface {...props} variant="degraded" />;
export const PermissionDeniedState = (props) => <StateSurface {...props} variant="permission-denied" />;
export const ErrorState = (props) => <StateSurface {...props} variant="error" />;
