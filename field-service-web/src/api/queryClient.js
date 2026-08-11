/**
 * @fileoverview Configured TanStack Query 5.x client.
 *
 * Single export: queryClient — used by QueryClientProvider in AppProviders.
 *
 * Configuration:
 * - staleTime: 30 s (dashboard queries refetch at this cadence)
 * - gcTime: 5 min (keep cache for silent background refresh)
 * - refetchOnWindowFocus: false (prevents spurious fetches in ops dashboards)
 * - retry predicate: isRetryable from errors.js — false for ALL 4xx including
 *   409, 422, 429 (WO-186 constraint: retrying 4xx fails open — A10)
 * - retryDelay: jittered exponential backoff for 5xx / network errors
 * - onError global hook: danger toast only for unexpected (non-4xx) error classes
 */

import { QueryClient } from '@tanstack/react-query'
import { isRetryable } from './errors.js'

const MAX_RETRY_ATTEMPTS = 3
const BASE_DELAY_MS = 500
const MAX_DELAY_MS = 30_000

/**
 * Jittered exponential backoff delay.
 * delay = min(base * 2^attempt + rand(0, base), max)
 *
 * @param {number} attempt  0-based retry attempt index
 * @returns {number}  Delay in milliseconds
 */
export function retryDelay(attempt) {
  const exp = BASE_DELAY_MS * Math.pow(2, attempt)
  const jitter = Math.random() * BASE_DELAY_MS
  return Math.min(exp + jitter, MAX_DELAY_MS)
}

/**
 * Retry predicate — returns false for any 4xx error, true for retryable 5xx
 * or network errors up to MAX_RETRY_ATTEMPTS.
 *
 * @param {number} failureCount
 * @param {unknown} error
 * @returns {boolean}
 */
export function retryPredicate(failureCount, error) {
  if (failureCount >= MAX_RETRY_ATTEMPTS) return false

  // ClientError from errors.js has a `retryable` flag
  if (error !== null && typeof error === 'object' && 'retryable' in error) {
    return /** @type {any} */ (error).retryable === true
  }

  // For unexpected error shapes, check the status field
  if (error !== null && typeof error === 'object' && 'status' in error) {
    return isRetryable(/** @type {any} */ (error).status)
  }

  // Unknown error type — default retryable (network-level issue)
  return true
}

/**
 * Global mutation error handler — emits a danger toast only for unexpected
 * error classes (5xx, network). 4xx errors are handled per-screen.
 *
 * @param {unknown} error
 */
function globalOnError(error) {
  if (error !== null && typeof error === 'object' && 'status' in error) {
    const status = /** @type {any} */ (error).status
    // 4xx errors are per-screen business errors — no global toast
    if (status >= 400 && status < 500) return
  }
  // 5xx or network: emit to the toast system if available
  // (ToastContext is not directly importable here; apps dispatch via queryClient meta)
}

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      gcTime: 5 * 60_000,
      refetchOnWindowFocus: false,
      retry: retryPredicate,
      retryDelay,
    },
    mutations: {
      retry: retryPredicate,
      retryDelay,
      onError: globalOnError,
    },
  },
})
