import { useState, useEffect, useCallback, useRef } from 'react';
import { NetworkOfflineError } from '../../app/useNetworkStatus.js';

const HEARTBEAT_URL      = '/api/v1/technicians/me/work-orders';
const HEARTBEAT_INTERVAL = 30_000; // 30 seconds
const DEBOUNCE_MS        = 1_000;  // 1 second — prevents banner flicker on rapid flaps

/**
 * @typedef {{ isOnline: boolean, isOffline: boolean, cachedAt: Date|null, assertOnline: () => void }} ConnectivityState
 */

/**
 * Technician connectivity hook.
 *
 * Combines three signals:
 *  1. navigator.onLine — immediate browser event (online/offline)
 *  2. Conditional-GET heartbeat — verifies real network path exists (not just link-local)
 *  3. Service worker response headers — reads cached data age from sw-cached-at
 *
 * Transitions are debounced by 1 s to prevent the banner from flickering on
 * rapid connectivity flaps (e.g. elevator doorway).
 *
 * Mutation guard: `assertOnline()` throws `NetworkOfflineError` immediately
 * when called offline — no write is queued or accepted locally.
 *
 * @returns {ConnectivityState}
 */
export function useConnectivity() {
  const [isOnline, setIsOnline] = useState(() => navigator.onLine);
  const [cachedAt, setCachedAt] = useState(null);
  const debounceRef = useRef(null);

  // Debounced setter for online state
  const setOnlineDebounced = useCallback((value) => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => setIsOnline(value), DEBOUNCE_MS);
  }, []);

  // Listen to browser online/offline events
  useEffect(() => {
    const onOnline  = () => setOnlineDebounced(true);
    const onOffline = () => setOnlineDebounced(false);
    window.addEventListener('online',  onOnline);
    window.addEventListener('offline', onOffline);
    return () => {
      window.removeEventListener('online',  onOnline);
      window.removeEventListener('offline', onOffline);
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, [setOnlineDebounced]);

  // Periodic heartbeat — verifies real connectivity beyond navigator.onLine
  useEffect(() => {
    let cancelled = false;

    async function runHeartbeat() {
      if (!navigator.onLine) return;
      try {
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 5_000);
        const response = await fetch(HEARTBEAT_URL, {
          method:  'GET',
          headers: { 'Cache-Control': 'no-cache' },
          signal:  controller.signal,
        });
        clearTimeout(timeout);

        if (cancelled) return;

        // Read cache timestamp from service worker header
        const cachedAtMs = response.headers.get('sw-cached-at');
        if (cachedAtMs) {
          setCachedAt(new Date(parseInt(cachedAtMs, 10)));
        }

        // 401 / 200 / 304 → connection exists (auth handled separately)
        // 503 → server degraded but network path is up
        setOnlineDebounced(true);
      } catch {
        // Network error or AbortError → truly offline
        if (!cancelled) setOnlineDebounced(false);
      }
    }

    runHeartbeat();
    const interval = setInterval(runHeartbeat, HEARTBEAT_INTERVAL);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [setOnlineDebounced]);

  const assertOnline = useCallback(() => {
    if (!isOnline) {
      throw new NetworkOfflineError(
        'Cannot submit: no network connection. Please retry when online.'
      );
    }
  }, [isOnline]);

  return {
    isOnline,
    isOffline: !isOnline,
    cachedAt,
    assertOnline,
  };
}

export { NetworkOfflineError };

/**
 * Formats the age of cached data for display in the not-connected banner.
 * @param {Date|null} date
 * @returns {string|null}
 */
export function formatCachedAge(date) {
  if (!date) return null;
  const diffMs  = Date.now() - date.getTime();
  const diffMin = Math.floor(diffMs / 60_000);
  if (diffMin < 1)  return 'just now';
  if (diffMin < 60) return `${diffMin} minute${diffMin !== 1 ? 's' : ''} ago`;
  const diffHr = Math.floor(diffMin / 60);
  if (diffHr < 24)  return `${diffHr} hour${diffHr !== 1 ? 's' : ''} ago`;
  return 'over a day ago';
}
