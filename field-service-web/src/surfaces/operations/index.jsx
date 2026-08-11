import React from 'react';
import { EmptyState } from '../../components/index.js';

/**
 * Operations surface — route group for /operations/*.
 *
 * This is the lazy-loaded entry chunk for the operations surface.
 * The charting library (Recharts) will be bundled into this chunk only.
 * See vite.config.js manualChunks — the 'operations' chunk is kept separate
 * so technicians on mobile connections never download charting bundles.
 *
 * Roles: MANAGER, ADMIN.
 * Full implementation is delivered by the Operations Dashboard epic.
 */
export default function OperationsSurface() {
  return (
    <EmptyState
      title="Operations Dashboard"
      description="The operations dashboard is being built. Check back soon."
    />
  );
}
