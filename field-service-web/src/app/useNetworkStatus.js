import { useEffect, useState, useCallback } from 'react';

/**
 * @typedef {{ isOnline: boolean, isOffline: boolean }} NetworkStatus
 */

/**
 * Tracks the browser's network connectivity.
 *
 * Mutation paths must call `assertOnline()` before submitting — when offline,
 * the call throws a NetworkOfflineError which the mutation handler maps to the
 * not-connected retry affordance. No write is queued or silently retried.
 *
 * Write queueing and conflict resolution are explicitly out of scope (WO-185).
 * The shell refuses writes it cannot commit rather than optimistically accepting them.
 *
 * @returns {NetworkStatus & { assertOnline: () => void }}
 */
export function useNetworkStatus() {
  const [isOnline, setIsOnline] = useState(() => navigator.onLine);

  useEffect(() => {
    const onOnline = () => setIsOnline(true);
    const onOffline = () => setIsOnline(false);

    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);

    return () => {
      window.removeEventListener('online', onOnline);
      window.removeEventListener('offline', onOffline);
    };
  }, []);

  const assertOnline = useCallback(() => {
    if (!navigator.onLine) {
      throw new NetworkOfflineError('Cannot submit: no network connection. Please retry when online.');
    }
  }, []);

  return { isOnline, isOffline: !isOnline, assertOnline };
}

/**
 * Thrown by `assertOnline()` when a mutation is attempted while offline.
 * Mutation handlers catch this and render the not-connected retry affordance.
 * No write is queued — the error is surfaced immediately so the user can retry.
 */
export class NetworkOfflineError extends Error {
  constructor(message) {
    super(message);
    this.name = 'NetworkOfflineError';
  }
}
