/**
 * @fileoverview Tracks browser online/offline state.
 * Drives the not-connected affordance — when offline, mutation paths must call
 * guardMutation() which returns false and the caller must NOT queue or
 * optimistically confirm the write (AC-8, WO-185 constraint).
 */
import { useState, useEffect, useCallback } from 'react'

/**
 * @returns {{ isOnline: boolean, guardMutation: () => boolean }}
 */
export function useNetworkStatus() {
  const [isOnline, setIsOnline] = useState(
    typeof navigator !== 'undefined' ? navigator.onLine : true
  )

  useEffect(() => {
    const handleOnline = () => setIsOnline(true)
    const handleOffline = () => setIsOnline(false)
    window.addEventListener('online', handleOnline)
    window.addEventListener('offline', handleOffline)
    return () => {
      window.removeEventListener('online', handleOnline)
      window.removeEventListener('offline', handleOffline)
    }
  }, [])

  /**
   * Returns true if the mutation may proceed; false when offline.
   * Callers MUST NOT queue or silently retry when this returns false —
   * show the not-connected retry affordance instead.
   */
  const guardMutation = useCallback(() => isOnline, [isOnline])

  return { isOnline, guardMutation }
}
