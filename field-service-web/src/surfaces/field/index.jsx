import React, { useEffect } from 'react';
import { EmptyState, DegradedState } from '../../components/index.js';
import { useNetworkStatus } from '../../app/useNetworkStatus.js';

/**
 * Field (Technician) surface — route group for /field/*.
 *
 * This is the lazy-loaded entry chunk for the field surface.
 * Roles: TECHNICIAN.
 *
 * The service worker is registered on mount of this surface (and only this
 * surface) so the technician's assigned-jobs list is cached for offline read
 * access. Write queueing is explicitly out of scope — the shell refuses writes
 * it cannot commit via useNetworkStatus + assertOnline().
 *
 * Full implementation is delivered by the Technician Mobile Workspace epic.
 */
export default function FieldSurface() {
  const { isOffline } = useNetworkStatus();

  useEffect(() => {
    if ('serviceWorker' in navigator) {
      import('../../serviceWorker/registerServiceWorker.js')
        .then(({ registerFieldServiceWorker }) => registerFieldServiceWorker())
        .catch((err) => {
          if (process.env.NODE_ENV !== 'production') {
            console.warn('[FieldSurface] Service worker registration failed:', err);
          }
        });
    }
  }, []);

  if (isOffline) {
    return (
      <DegradedState
        title="You are offline"
        description="Showing your cached job list. New assignments and status updates require a connection."
      />
    );
  }

  return (
    <EmptyState
      title="My Jobs"
      description="Your assigned jobs will appear here."
    />
  );
}
