/**
 * Configured TanStack Query client — single export used by the entire app.
 *
 * Retry policy:
 * - 4xx responses are NEVER retried (AC-1 mandate, A10 constraint).
 *   Retrying a 422 guard refusal or 409 illegal transition fails open.
 * - 5xx and network errors retry up to MAX_RETRIES with jittered backoff.
 *
 * Update AppProviders.jsx to import { queryClient } from './api/queryClient.js'
 * instead of constructing a local QueryClient instance.
 */

import { QueryClient } from '@tanstack/react-query';

const MAX_RETRIES = 3;

/**
 * Jittered exponential backoff capped at 30 seconds.
 * Full-jitter: base * (0.5 + Math.random() * 0.5)
 *
 * @param {number} attemptIndex  0-based retry count
 * @returns {number}  delay in ms
 */
export function jitteredBackoff(attemptIndex) {
  const base = Math.min(30_000, 1_000 * Math.pow(2, attemptIndex));
  return Math.round(base * (0.5 + Math.random() * 0.5));
}

/**
 * Retry predicate.
 *
 * Returns false for:
 * - 4xx errors (including 409, 422, 429) — never retry business rule failures
 * - After MAX_RETRIES attempts
 *
 * Returns true for:
 * - Network errors (status 0) up to MAX_RETRIES
 * - 5xx errors up to MAX_RETRIES
 *
 * @param {number} failureCount  1-based attempt index (TanStack convention)
 * @param {unknown} error
 * @returns {boolean}
 */
export function shouldRetry(failureCount, error) {
  if (failureCount >= MAX_RETRIES) return false;
  if (error && typeof error === 'object') {
    const status = error.status;
    if (status != null && status >= 400 && status < 500) return false;
    if (error.retryable === false) return false;
  }
  return true;
}

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      gcTime: 5 * 60_000,
      refetchOnWindowFocus: false,
      retry: shouldRetry,
      retryDelay: jitteredBackoff,
    },
    mutations: {
      retry: 0,
    },
  },
});
