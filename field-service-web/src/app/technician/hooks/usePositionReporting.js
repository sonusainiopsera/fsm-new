import { useEffect, useRef, useCallback } from 'react';
import { usePositionSharing } from '../PositionSharingContext.js';

const THROTTLE_MS     = 60_000;
const ACTIVE_STATES   = new Set(['EN_ROUTE', 'IN_PROGRESS']);

const GEO_OPTIONS = {
  enableHighAccuracy: true,
  timeout: 15_000,
  maximumAge: 30_000,
};

/**
 * Starts watchPosition when job state is EN_ROUTE or IN_PROGRESS.
 * Throttles sends to at most one per 60 seconds.
 * Stops on unmount, background, completion, hold, or geolocation error.
 *
 * @param {string|null} jobState   current work order state string
 * @param {string}      workOrderId work order ID (unused in body but available for future use)
 */
export function usePositionReporting(jobState, workOrderId) {
  const { setSharing } = usePositionSharing();
  const watchIdRef     = useRef(null);
  const lastSentRef    = useRef(0);
  const activeRef      = useRef(false);

  const shouldReport = ACTIVE_STATES.has(jobState);

  const sendPosition = useCallback(async (position) => {
    const now = Date.now();
    if (now - lastSentRef.current < THROTTLE_MS) return;
    lastSentRef.current = now;

    const { latitude, longitude, accuracy } = position.coords;
    try {
      await fetch('/api/v1/technicians/me/position', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          latitude,
          longitude,
          accuracyMetres: Math.round(accuracy),
          capturedAt: new Date(position.timestamp).toISOString(),
        }),
      });
    } catch (_) {
      // Network errors are silently swallowed — never block the technician's workflow
    }
  }, []);

  const stopWatch = useCallback(() => {
    if (watchIdRef.current !== null) {
      navigator.geolocation.clearWatch(watchIdRef.current);
      watchIdRef.current = null;
    }
    if (activeRef.current) {
      activeRef.current = false;
      setSharing(false);
    }
  }, [setSharing]);

  useEffect(() => {
    if (!shouldReport) {
      stopWatch();
      return;
    }

    if (!navigator.geolocation) {
      // Geolocation not supported — degrade silently
      return;
    }

    // Start watching
    activeRef.current = true;
    setSharing(true);

    watchIdRef.current = navigator.geolocation.watchPosition(
      sendPosition,
      (err) => {
        // PERMISSION_DENIED, POSITION_UNAVAILABLE, TIMEOUT — stop silently
        stopWatch();
      },
      GEO_OPTIONS
    );

    // Stop when app is backgrounded
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'hidden') {
        stopWatch();
      } else if (document.visibilityState === 'visible' && shouldReport) {
        // Resume on foreground
        if (watchIdRef.current === null && navigator.geolocation) {
          activeRef.current = true;
          setSharing(true);
          watchIdRef.current = navigator.geolocation.watchPosition(
            sendPosition,
            () => stopWatch(),
            GEO_OPTIONS
          );
        }
      }
    };

    document.addEventListener('visibilitychange', handleVisibilityChange);

    return () => {
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      stopWatch();
    };
  }, [shouldReport, sendPosition, stopWatch, setSharing]);
}
