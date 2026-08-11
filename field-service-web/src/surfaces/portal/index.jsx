import React from 'react';
import { EmptyState } from '../../components/index.js';

/**
 * Customer Portal surface — route group for /portal/*.
 *
 * This is the lazy-loaded entry chunk for the customer portal surface.
 * Roles: CUSTOMER.
 * Full implementation is delivered by the Customer Self-Service Portal epic.
 */
export default function PortalSurface() {
  return (
    <EmptyState
      title="Customer Portal"
      description="Your service requests and job history will appear here."
    />
  );
}
