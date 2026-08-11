/**
 * @fileoverview Field surface — Technician mobile PWA.
 * Registers the field service worker (read-only assigned-jobs cache).
 * Placeholder until WO-field implements the technician workspace.
 */
import { useEffect } from 'react'
import { Routes, Route } from 'react-router-dom'
import { EmptyState, DegradedState } from '../../components/index.js'
import { useNetworkStatus } from '../../app/useNetworkStatus.js'
import { registerFieldServiceWorker } from '../../serviceWorker/register.js'

function JobsList({ stale }) {
  const { isOnline } = useNetworkStatus()

  if (!isOnline && stale) {
    return <DegradedState message="Showing cached job list. Live data will update when connectivity is restored." />
  }

  return <EmptyState message="My Jobs — coming soon." />
}

export default function FieldSurface() {
  useEffect(() => {
    registerFieldServiceWorker()
  }, [])

  return (
    <Routes>
      <Route index element={<EmptyState message="Field surface — coming soon." />} />
      <Route path="jobs" element={<JobsList stale={false} />} />
      <Route path="*" element={<EmptyState message="Field surface — coming soon." />} />
    </Routes>
  )
}
