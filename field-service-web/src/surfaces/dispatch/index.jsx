import React from 'react';
import { EmptyState } from '../../components/index.js';

/**
 * Dispatcher surface — route group for /dispatch/*.
 *
 * This is the lazy-loaded entry chunk for the dispatch surface.
 * Roles: DISPATCHER, ADMIN, MANAGER.
 *
 * Full implementation is delivered by later epics (Dispatcher Work Order Board,
 * AI Smart Dispatch). This stub ensures the route exists, the chunk is split,
 * and integration tests can verify the lazy-load lifecycle.
 */
export default function DispatchSurface() {
  return (
    <EmptyState
      title="Dispatch Board"
      description="The dispatcher board is being built. Check back soon."
    />
  );
}
